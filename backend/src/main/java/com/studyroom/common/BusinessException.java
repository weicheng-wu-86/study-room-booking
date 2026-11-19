package com.studyroom.common;

import lombok.Getter;

/**
 * 业务异常。code 直接使用 HTTP 状态码语义：
 * 400 参数或规则不满足，401 未登录，403 无权限，404 资源不存在，409 资源冲突（座位被占、状态已变化）。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(403, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }
}
