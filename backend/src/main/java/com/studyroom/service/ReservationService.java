package com.studyroom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.studyroom.common.BusinessException;
import com.studyroom.common.PageResult;
import com.studyroom.config.AppProperties;
import com.studyroom.dto.Requests;
import com.studyroom.entity.Reservation;
import com.studyroom.entity.Seat;
import com.studyroom.entity.SeatOccupancy;
import com.studyroom.entity.StudyRoom;
import com.studyroom.entity.User;
import com.studyroom.enums.ReservationStatus;
import com.studyroom.mapper.ReservationMapper;
import com.studyroom.mapper.SeatMapper;
import com.studyroom.mapper.SeatOccupancyMapper;
import com.studyroom.mapper.StudyRoomMapper;
import com.studyroom.mapper.UserMapper;
import com.studyroom.vo.ReservationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 预约业务：创建、取消、签到、暂离、返回、签退，以及各类查询。
 * <p>
 * 并发安全由三层共同保证：
 * <ol>
 *   <li>Redis 分布式锁：同一用户、同一座位同一天的预约请求串行执行，把竞争挡在数据库之外；</li>
 *   <li>seat_occupancy 表的两个唯一索引：即使锁失效，数据库也不会接受重复占用（最终防线）；</li>
 *   <li>带条件的状态更新：签到、取消等状态流转只在当前状态符合预期时才生效。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MDHM = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final ReservationMapper reservationMapper;
    private final SeatOccupancyMapper occupancyMapper;
    private final SeatMapper seatMapper;
    private final StudyRoomMapper roomMapper;
    private final UserMapper userMapper;
    private final ReservationLifecycleService lifecycle;
    private final RedisLock redisLock;
    private final TransactionTemplate transactionTemplate;
    private final AppProperties props;
    private final Clock clock;

    // =============================== 创建预约 ===============================

    public ReservationVO create(Long userId, Requests.CreateReservation req) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 第一步：与并发无关的校验，放在锁外，尽早拒绝无效请求
        User user = userMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw BusinessException.forbidden("账号不可用");
        }
        if (user.getBanUntil() != null && user.getBanUntil().isAfter(now)) {
            throw BusinessException.forbidden("因多次违约，您在 " + user.getBanUntil().format(MDHM) + " 前无法预约");
        }
        Seat seat = seatMapper.selectById(req.seatId());
        if (seat == null || !Boolean.TRUE.equals(seat.getEnabled())) {
            throw BusinessException.badRequest("座位不存在或已暂停使用");
        }
        StudyRoom room = roomMapper.selectById(seat.getRoomId());
        if (room == null || !Boolean.TRUE.equals(room.getEnabled())) {
            throw BusinessException.badRequest("该自习室暂未开放");
        }
        validateTime(room, req, now);
        // 锁外预检查：座位已被占用时直接返回，不必排队等锁。
        // 抢座高峰时绝大多数请求在这里就结束了；它只是优化，正确性仍由锁内的复查保证。
        if (occupancyMapper.countSeatConflict(seat.getId(), req.date(), req.startHour(), req.endHour()) > 0) {
            throw BusinessException.conflict("该座位在所选时段已被预约，请选择其他座位或时段");
        }

        // 第二步：加锁。先用户锁、后座位锁，所有请求加锁顺序一致，不会互相等待形成死锁。
        // 锁必须包在事务外面：若先开事务再加锁，锁释放时事务可能尚未提交，
        // 下一个拿到锁的请求会读不到刚写入的占用记录。
        String userKey = "lock:reserve:user:" + userId;
        String seatKey = "lock:reserve:seat:" + seat.getId() + ":" + req.date();
        Long id = redisLock.withLock(userKey, () ->
                redisLock.withLock(seatKey, () ->
                        transactionTemplate.execute(status -> doCreate(userId, seat, req, now))));
        return detail(id);
    }

    /** 在锁和事务内执行：冲突检查 → 写预约 → 写占用 */
    private Long doCreate(Long userId, Seat seat, Requests.CreateReservation req, LocalDateTime now) {
        int start = req.startHour();
        int end = req.endHour();
        LocalDate date = req.date();

        if (occupancyMapper.countSeatConflict(seat.getId(), date, start, end) > 0) {
            throw BusinessException.conflict("该座位在所选时段已被预约，请选择其他座位或时段");
        }
        if (occupancyMapper.countUserConflict(userId, date, start, end) > 0) {
            throw BusinessException.conflict("您在所选时段已有其他预约");
        }
        int maxPerDay = props.getReserve().getMaxHoursPerDay();
        int used = reservationMapper.sumHoursOfDay(userId, date);
        if (used + (end - start) > maxPerDay) {
            throw BusinessException.badRequest("每人每天最多预约 " + maxPerDay + " 小时，您当天已预约 " + used + " 小时");
        }

        Reservation r = new Reservation();
        r.setUserId(userId);
        r.setSeatId(seat.getId());
        r.setRoomId(seat.getRoomId());
        r.setReserveDate(date);
        r.setStartHour(start);
        r.setEndHour(end);
        r.setStatus(ReservationStatus.RESERVED);
        r.setAwayCount(0);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        reservationMapper.insert(r);

        List<SeatOccupancy> slots = new ArrayList<>(end - start);
        for (int hour = start; hour < end; hour++) {
            slots.add(new SeatOccupancy(null, seat.getId(), userId, date, hour, r.getId()));
        }
        try {
            occupancyMapper.insertBatch(slots);
        } catch (DuplicateKeyException e) {
            // 走到这里说明锁没有挡住（例如锁过期），由唯一索引兜底；抛出异常使事务回滚，刚插入的预约记录一并撤销
            log.warn("唯一索引拦截了一次重复占用: seat={}, date={}, {}-{}", seat.getId(), date, start, end);
            throw BusinessException.conflict("手慢了，该座位刚刚被其他同学预约");
        }
        return r.getId();
    }

    private void validateTime(StudyRoom room, Requests.CreateReservation req, LocalDateTime now) {
        AppProperties.Reserve rule = props.getReserve();
        LocalDate today = now.toLocalDate();
        LocalDate date = req.date();
        int start = req.startHour();
        int end = req.endHour();

        if (date.isBefore(today) || date.isAfter(today.plusDays(rule.getMaxDaysAhead()))) {
            throw BusinessException.badRequest("只能预约今天起 " + (rule.getMaxDaysAhead() + 1) + " 天内的座位");
        }
        if (start >= end) {
            throw BusinessException.badRequest("结束时间必须晚于开始时间");
        }
        if (start < room.getOpenHour() || end > room.getCloseHour()) {
            throw BusinessException.badRequest("该自习室开放时间为 " + room.getOpenHour() + ":00-" + room.getCloseHour() + ":00");
        }
        if (end - start > rule.getMaxHoursPerReservation()) {
            throw BusinessException.badRequest("单次预约最长 " + rule.getMaxHoursPerReservation() + " 小时");
        }
        // 当天允许预约“当前这个小时”（已经开始但未结束），不允许预约已经过去的小时
        if (date.isEqual(today) && start < now.getHour()) {
            throw BusinessException.badRequest("不能预约已经过去的时段");
        }
    }

    // =============================== 状态流转 ===============================

    /** 取消预约：只有待签到的预约可以取消，取消后立即释放座位 */
    @Transactional
    public void cancel(Long userId, Long id) {
        Reservation r = requireOwned(userId, id);
        if (r.getStatus() != ReservationStatus.RESERVED) {
            throw BusinessException.badRequest("只有待签到的预约可以取消");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        boolean ok = reservationMapper.transit(id, ReservationStatus.RESERVED, ReservationStatus.CANCELLED, now,
                w -> w.set(Reservation::getRemark, "用户取消"));
        if (!ok) {
            throw staleState();
        }
        occupancyMapper.deleteByReservation(id);
    }

    /**
     * 扫码签到。code 是印在座位二维码里的签到码：
     * 用它找到座位，再找到当前用户在这个座位上、此刻处于签到时间窗内的预约。
     */
    @Transactional
    public ReservationVO checkin(Long userId, String code) {
        LocalDateTime now = LocalDateTime.now(clock);
        Seat seat = seatMapper.selectOne(new LambdaQueryWrapper<Seat>().eq(Seat::getCheckinCode, code.trim().toUpperCase()));
        if (seat == null) {
            throw BusinessException.badRequest("签到码无效");
        }
        List<Reservation> candidates = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getSeatId, seat.getId())
                .eq(Reservation::getReserveDate, now.toLocalDate())
                .eq(Reservation::getStatus, ReservationStatus.RESERVED)
                .orderByAsc(Reservation::getStartHour));
        if (candidates.isEmpty()) {
            throw BusinessException.badRequest("您今天在座位 " + seat.getSeatNo() + " 没有待签到的预约");
        }
        AppProperties.Reserve rule = props.getReserve();
        for (Reservation r : candidates) {
            LocalDateTime open = r.startTime().minusMinutes(rule.getCheckinEarlyMinutes());
            LocalDateTime deadline = r.checkinDeadline(rule.getCheckinLateMinutes());
            if (now.isBefore(open)) {
                throw BusinessException.badRequest("还未到签到时间，请在 " + open.format(HM) + " 之后签到");
            }
            if (!now.isAfter(deadline)) {
                boolean ok = reservationMapper.transit(r.getId(), ReservationStatus.RESERVED, ReservationStatus.IN_USE, now,
                        w -> w.set(Reservation::getCheckinTime, now));
                if (!ok) {
                    throw staleState();
                }
                return detail(r.getId());
            }
            // 已过截止时间的预约留给定时任务判定为爽约，继续看下一条
        }
        throw BusinessException.badRequest("已超过签到截止时间，本次预约将按爽约处理");
    }

    /** 暂离：保留座位，但必须在限定时间内返回 */
    @Transactional
    public ReservationVO away(Long userId, Long id) {
        Reservation r = requireOwned(userId, id);
        if (r.getStatus() != ReservationStatus.IN_USE) {
            throw BusinessException.badRequest("只有使用中的预约可以暂离");
        }
        int maxTimes = props.getReserve().getAwayMaxTimes();
        if (r.getAwayCount() >= maxTimes) {
            throw BusinessException.badRequest("每次预约最多暂离 " + maxTimes + " 次");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        boolean ok = reservationMapper.transit(id, ReservationStatus.IN_USE, ReservationStatus.AWAY, now,
                w -> w.set(Reservation::getAwayStartTime, now).setSql("away_count = away_count + 1"));
        if (!ok) {
            throw staleState();
        }
        return detail(id);
    }

    /** 暂离返回。若已超时，则不等定时任务，直接按暂离超时处理 */
    public ReservationVO back(Long userId, Long id) {
        Reservation r = requireOwned(userId, id);
        if (r.getStatus() != ReservationStatus.AWAY) {
            throw BusinessException.badRequest("当前预约不在暂离状态");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime deadline = r.getAwayStartTime().plusMinutes(props.getReserve().getAwayMaxMinutes());
        if (now.isAfter(deadline)) {
            lifecycle.markAwayTimeout(r, now);
            throw BusinessException.badRequest("暂离已超时，座位已被释放");
        }
        boolean ok = reservationMapper.transit(id, ReservationStatus.AWAY, ReservationStatus.IN_USE, now,
                w -> w.set(Reservation::getAwayStartTime, null));
        if (!ok) {
            throw staleState();
        }
        return detail(id);
    }

    /** 签退：提前离开时释放剩余时段，让其他同学可以预约 */
    @Transactional
    public ReservationVO checkout(Long userId, Long id) {
        Reservation r = requireOwned(userId, id);
        if (r.getStatus() != ReservationStatus.IN_USE && r.getStatus() != ReservationStatus.AWAY) {
            throw BusinessException.badRequest("只有使用中的预约可以签退");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        boolean ok = reservationMapper.transit(id, r.getStatus(), ReservationStatus.COMPLETED, now,
                w -> w.set(Reservation::getCheckoutTime, now).set(Reservation::getAwayStartTime, null));
        if (!ok) {
            throw staleState();
        }
        lifecycle.releaseRemaining(r, now);
        return detail(id);
    }

    /** 管理员取消预约（座位维修、临时闭馆等），不记违约 */
    @Transactional
    public void adminCancel(Long id, String reason) {
        Reservation r = reservationMapper.selectById(id);
        if (r == null) {
            throw BusinessException.notFound("预约不存在");
        }
        if (!r.getStatus().isActive()) {
            throw BusinessException.badRequest("该预约已结束，无需取消");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String remark = "管理员取消" + (reason == null || reason.isBlank() ? "" : "：" + reason.trim());
        boolean ok = reservationMapper.transit(id, r.getStatus(), ReservationStatus.CANCELLED, now,
                w -> w.set(Reservation::getRemark, remark).set(Reservation::getAwayStartTime, null));
        if (!ok) {
            throw staleState();
        }
        if (r.getStatus() == ReservationStatus.RESERVED) {
            occupancyMapper.deleteByReservation(id);
        } else {
            lifecycle.releaseRemaining(r, now);
        }
    }

    // =============================== 查询 ===============================

    public ReservationVO detail(Long id) {
        ReservationVO vo = reservationMapper.selectVOById(id);
        if (vo == null) {
            throw BusinessException.notFound("预约不存在");
        }
        return decorate(vo);
    }

    /** 当前用户仍有效的预约（首页卡片） */
    public List<ReservationVO> active(Long userId) {
        List<ReservationVO> list = reservationMapper.selectActiveByUser(userId);
        list.forEach(this::decorate);
        return list;
    }

    public PageResult<ReservationVO> myPage(Long userId, int page, int size, String status) {
        var result = reservationMapper.selectMyPage(new Page<>(page, size), userId, normalizeStatus(status));
        result.getRecords().forEach(this::decorate);
        return PageResult.of(result);
    }

    public PageResult<ReservationVO> adminPage(int page, int size, LocalDate date, Long roomId,
                                               String status, String keyword) {
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        var result = reservationMapper.selectAdminPage(new Page<>(page, size), date, roomId, normalizeStatus(status), kw);
        result.getRecords().forEach(this::decorate);
        return PageResult.of(result);
    }

    // =============================== 内部方法 ===============================

    /** 计算签到时间窗与暂离截止时间，供前端展示 */
    private ReservationVO decorate(ReservationVO vo) {
        AppProperties.Reserve rule = props.getReserve();
        LocalDateTime start = vo.getReserveDate().atStartOfDay().plusHours(vo.getStartHour());
        LocalDateTime base = vo.getCreatedAt() != null && vo.getCreatedAt().isAfter(start) ? vo.getCreatedAt() : start;
        vo.setCheckinOpenTime(start.minusMinutes(rule.getCheckinEarlyMinutes()));
        vo.setCheckinDeadline(base.plusMinutes(rule.getCheckinLateMinutes()));
        if (vo.getStatus() == ReservationStatus.AWAY && vo.getAwayStartTime() != null) {
            vo.setAwayDeadline(vo.getAwayStartTime().plusMinutes(rule.getAwayMaxMinutes()));
        }
        return vo;
    }

    /** 读取预约并确认属于当前用户；不属于时同样返回“不存在”，不泄露他人预约是否存在 */
    private Reservation requireOwned(Long userId, Long id) {
        Reservation r = reservationMapper.selectById(id);
        if (r == null || !r.getUserId().equals(userId)) {
            throw BusinessException.notFound("预约不存在");
        }
        return r;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ReservationStatus.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("未知的预约状态：" + status);
        }
    }

    private static BusinessException staleState() {
        return BusinessException.conflict("预约状态已发生变化，请刷新后重试");
    }
}
