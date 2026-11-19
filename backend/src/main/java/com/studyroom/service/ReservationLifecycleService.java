package com.studyroom.service;

import com.studyroom.config.AppProperties;
import com.studyroom.entity.Reservation;
import com.studyroom.enums.ReservationStatus;
import com.studyroom.enums.ViolationType;
import com.studyroom.mapper.ReservationMapper;
import com.studyroom.mapper.SeatOccupancyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 由系统（定时任务）触发的预约状态流转：爽约释放、暂离超时释放、到点自动结束。
 * <p>
 * 每个方法是一个独立的小事务，且都先做带条件的状态更新（见 {@link ReservationMapper#transit}），
 * 更新成功才继续释放座位、记违约。因此即使定时任务与用户操作同时发生，
 * 或者多个应用实例重复执行，同一条预约也只会被处理一次。
 */
@Service
@RequiredArgsConstructor
public class ReservationLifecycleService {

    private final ReservationMapper reservationMapper;
    private final SeatOccupancyMapper occupancyMapper;
    private final ViolationService violationService;
    private final AppProperties props;

    /** 超时未签到：RESERVED → NO_SHOW，释放整段座位并记违约 */
    @Transactional
    public boolean markNoShow(Reservation r, LocalDateTime now) {
        if (!reservationMapper.transit(r.getId(), ReservationStatus.RESERVED, ReservationStatus.NO_SHOW, now, null)) {
            return false;
        }
        occupancyMapper.deleteByReservation(r.getId());
        violationService.record(r.getUserId(), r.getId(), ViolationType.NO_SHOW,
                "预约后超过 " + props.getReserve().getCheckinLateMinutes() + " 分钟未签到", now);
        return true;
    }

    /** 暂离超时：AWAY → AWAY_TIMEOUT，释放之后的小时并记违约 */
    @Transactional
    public boolean markAwayTimeout(Reservation r, LocalDateTime now) {
        boolean ok = reservationMapper.transit(r.getId(), ReservationStatus.AWAY, ReservationStatus.AWAY_TIMEOUT, now,
                w -> w.set(Reservation::getCheckoutTime, now).set(Reservation::getAwayStartTime, null));
        if (!ok) {
            return false;
        }
        releaseRemaining(r, now);
        violationService.record(r.getUserId(), r.getId(), ViolationType.AWAY_TIMEOUT,
                "暂离超过 " + props.getReserve().getAwayMaxMinutes() + " 分钟未返回", now);
        return true;
    }

    /** 到达结束时间：IN_USE / AWAY → COMPLETED，签退时间记为预约结束时刻 */
    @Transactional
    public boolean autoComplete(Reservation r, LocalDateTime now) {
        return reservationMapper.transit(r.getId(), r.getStatus(), ReservationStatus.COMPLETED, now,
                w -> w.set(Reservation::getCheckoutTime, r.endTime()).set(Reservation::getAwayStartTime, null));
    }

    /**
     * 释放一条预约尚未开始的小时，让其他同学可以预约。
     * 当前所在的小时保留不释放：这一小时已经开始，且保留它可以避免同一用户立刻重复占座。
     */
    public void releaseRemaining(Reservation r, LocalDateTime now) {
        if (now.toLocalDate().isBefore(r.getReserveDate())) {
            occupancyMapper.deleteByReservation(r.getId());
        } else if (now.toLocalDate().isEqual(r.getReserveDate())) {
            occupancyMapper.deleteAfterHour(r.getId(), now.getHour());
        }
        // 预约日期已过：占用行由每日清理任务删除，这里无需处理
    }
}
