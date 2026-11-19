package com.studyroom.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyroom.config.AppProperties;
import com.studyroom.entity.Reservation;
import com.studyroom.enums.ReservationStatus;
import com.studyroom.mapper.ReservationMapper;
import com.studyroom.mapper.SeatOccupancyMapper;
import com.studyroom.service.RedisLock;
import com.studyroom.service.ReservationLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务：周期性扫描仍有效的预约，处理三类到期情况。
 * <ol>
 *   <li>待签到且已过签到截止时间 → 爽约，释放座位，记违约；</li>
 *   <li>暂离且已超过最长暂离时间 → 暂离超时，释放剩余时段，记违约；</li>
 *   <li>使用中或暂离且已到结束时间 → 自动结束。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationScheduler {

    private static final String JOB_LOCK_KEY = "lock:job:reservation-scan";

    private final ReservationMapper reservationMapper;
    private final SeatOccupancyMapper occupancyMapper;
    private final ReservationLifecycleService lifecycle;
    private final RedisLock redisLock;
    private final AppProperties props;
    private final Clock clock;

    /**
     * 每隔一段时间扫描一次。部署多个实例时用 Redis 锁保证同一时刻只有一个实例在扫描；
     * 即使锁失效导致重复扫描，状态流转本身带条件更新，也不会重复处理。
     */
    @Scheduled(fixedDelayString = "${studyroom.reserve.scan-interval-ms:30000}")
    public void scheduledScan() {
        String token = redisLock.tryLock(JOB_LOCK_KEY, Duration.ofMinutes(2), Duration.ZERO);
        if (token == null) {
            return;
        }
        try {
            int handled = scanOnce();
            if (handled > 0) {
                log.info("定时扫描处理了 {} 条到期预约", handled);
            }
        } finally {
            redisLock.unlock(JOB_LOCK_KEY, token);
        }
    }

    /**
     * 执行一次扫描，返回处理的预约数。单独成一个方法，便于测试直接调用。
     */
    public int scanOnce() {
        LocalDateTime now = LocalDateTime.now(clock);
        AppProperties.Reserve rule = props.getReserve();
        // 只需检查“今天及以前”仍处于有效状态的预约，未来的预约不可能到期
        List<Reservation> candidates = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .in(Reservation::getStatus, ReservationStatus.RESERVED, ReservationStatus.IN_USE, ReservationStatus.AWAY)
                .le(Reservation::getReserveDate, now.toLocalDate()));

        int handled = 0;
        for (Reservation r : candidates) {
            try {
                if (handle(r, now, rule)) {
                    handled++;
                }
            } catch (Exception e) {
                // 单条失败不影响其余预约，下一轮扫描会重试
                log.error("处理到期预约 {} 失败", r.getId(), e);
            }
        }
        return handled;
    }

    private boolean handle(Reservation r, LocalDateTime now, AppProperties.Reserve rule) {
        switch (r.getStatus()) {
            case RESERVED:
                if (now.isAfter(r.checkinDeadline(rule.getCheckinLateMinutes()))) {
                    return lifecycle.markNoShow(r, now);
                }
                return false;
            case AWAY:
                if (r.getAwayStartTime() != null
                        && now.isAfter(r.getAwayStartTime().plusMinutes(rule.getAwayMaxMinutes()))) {
                    return lifecycle.markAwayTimeout(r, now);
                }
                // 暂离未超时但预约已到结束时间，按正常结束处理
                return !now.isBefore(r.endTime()) && lifecycle.autoComplete(r, now);
            case IN_USE:
                return !now.isBefore(r.endTime()) && lifecycle.autoComplete(r, now);
            default:
                return false;
        }
    }

    /** 每天凌晨清理昨天及更早的占用记录，使占用表的数据量保持稳定 */
    @Scheduled(cron = "0 30 3 * * *", zone = "${studyroom.zone:Asia/Shanghai}")
    public void cleanExpiredOccupancy() {
        int deleted = occupancyMapper.deleteBefore(LocalDateTime.now(clock).toLocalDate());
        log.info("清理过期占用记录 {} 行", deleted);
    }
}
