package com.studyroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.studyroom.enums.Role;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户（学生或管理员） */
@Data
@TableName("sys_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** BCrypt 密文 */
    private String password;
    private String realName;
    private String studentNo;
    private String phone;
    private Role role;
    private Boolean enabled;
    /** 禁约截止时间，为空或已过期表示可正常预约 */
    private LocalDateTime banUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
