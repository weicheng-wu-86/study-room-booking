package com.studyroom.security;

import com.studyroom.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录与权限拦截器：
 * 1. 除登录、注册外的所有 /api/** 请求都必须携带有效的 JWT；
 * 2. /api/admin/** 只允许管理员访问。
 * 这里抛出的异常同样会被 GlobalExceptionHandler 处理成统一的 JSON 响应。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 浏览器的跨域预检请求不带令牌，直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        LoginUser user = null;
        if (header != null && header.startsWith(BEARER)) {
            user = jwtUtil.parse(header.substring(BEARER.length()));
        }
        if (user == null) {
            throw new BusinessException(401, "登录已失效，请重新登录");
        }
        if (request.getRequestURI().startsWith("/api/admin/") && !user.isAdmin()) {
            throw BusinessException.forbidden("没有访问该功能的权限");
        }
        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // Tomcat 线程会被复用，必须清理，否则下一个请求可能读到上一个用户
        UserContext.clear();
    }
}
