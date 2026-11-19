package com.studyroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.studyroom.enums.ReservationStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 预约记录。时段为 [startHour, endHour)，以整点小时为粒度 */
@Data
@TableName("reservation")
public class Reservation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long seatId;
    private Long roomId;
    private LocalDate reserveDate;
    private Integer startHour;
    private Integer endHour;
    private ReservationStatus status;
    private LocalDateTime checkinTime;
    private LocalDateTime checkoutTime;
    private LocalDateTime awayStartTime;
    private Integer awayCount;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 预约开始时刻 */
    public LocalDateTime startTime() {
        return reserveDate.atStartOfDay().plusHours(startHour);
    }

    /** 预约结束时刻（endHour 可能为 24，因此用 plusHours 而不是 LocalTime.of） */
    public LocalDateTime endTime() {
        return reserveDate.atStartOfDay().plusHours(endHour);
    }

    /**
     * 签到截止时刻：开始后 lateMinutes 分钟。
     * 若预约是在时段开始之后才提交的（预约当前小时），则从提交时刻起算，保证学生有时间赶到。
     */
    public LocalDateTime checkinDeadline(int lateMinutes) {
        LocalDateTime base = createdAt != null && createdAt.isAfter(startTime()) ? createdAt : startTime();
        return base.plusMinutes(lateMinutes);
    }
}
