package com.studyroom.security;

import com.studyroom.common.BusinessException;

/**
 * 用 ThreadLocal 在一次请求内保存当前登录用户，Controller 和 Service 可随处取用。
 * 由 {@link AuthInterceptor} 在请求开始时写入、请求结束时清除。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        LoginUser user = HOLDER.get();
        if (user == null) {
            throw new BusinessException(401, "请先登录");
        }
        return user;
    }

    public static Long currentId() {
        return get().id();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
