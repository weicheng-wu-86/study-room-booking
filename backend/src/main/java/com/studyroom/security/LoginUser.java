package com.studyroom.security;

import com.studyroom.enums.Role;

/**
 * 当前登录用户，从 JWT 中解析得到。
 */
public record LoginUser(Long id, String username, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
