package com.studyroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.studyroom.enums.ViolationType;
import lombok.Data;

import java.time.LocalDateTime;

/** 违约记录 */
@Data
@TableName("violation")
public class Violation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long reservationId;
    private ViolationType type;
    private String remark;
    private LocalDateTime createdAt;
}
