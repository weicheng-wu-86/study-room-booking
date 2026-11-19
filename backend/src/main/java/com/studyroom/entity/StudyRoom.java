package com.studyroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 自习室 */
@Data
@TableName("study_room")
public class StudyRoom {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String location;
    private String description;
    /** 开放开始整点（含），0-23 */
    private Integer openHour;
    /** 开放结束整点（不含），1-24 */
    private Integer closeHour;
    private Integer rowCount;
    private Integer colCount;
    private Boolean enabled;
    private LocalDateTime createdAt;
}
