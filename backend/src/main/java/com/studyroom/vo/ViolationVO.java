package com.studyroom.vo;

import com.studyroom.enums.ViolationType;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 违约记录详情（联表查询结果）。
 */
@Data
public class ViolationVO {

    private Long id;
    private Long userId;
    private Long reservationId;
    private ViolationType type;
    private String remark;
    private LocalDateTime createdAt;

    private String username;
    private String realName;
    private String studentNo;

    private String roomName;
    private String seatNo;
    private LocalDate reserveDate;
    private Integer startHour;
    private Integer endHour;
}
