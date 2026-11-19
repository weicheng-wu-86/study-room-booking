package com.studyroom.vo;

import com.studyroom.enums.ReservationStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预约详情：预约记录 + 座位、自习室、预约人信息（联表查询结果）。
 */
@Data
public class ReservationVO {

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

    // 联表字段
    private String seatNo;
    private String roomName;
    private String roomLocation;
    private String username;
    private String realName;
    private String studentNo;

    // 由 Service 计算后填充，前端据此展示倒计时与按钮
    /** 最早可签到时刻 */
    private LocalDateTime checkinOpenTime;
    /** 签到截止时刻 */
    private LocalDateTime checkinDeadline;
    /** 暂离必须返回的时刻（仅暂离中有值） */
    private LocalDateTime awayDeadline;
}
