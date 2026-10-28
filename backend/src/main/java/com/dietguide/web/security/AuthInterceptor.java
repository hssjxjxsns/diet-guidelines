package com.dietguide.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 本地单用户模式：数据库与后端都跑在同一台机器上，不再校验 JWT，
 * 所有请求统一归属内置用户 local-user。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID_ATTRIBUTE = "dietguide.userId";
    public static final String LOCAL_USER_ID = "local-user";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        request.setAttribute(USER_ID_ATTRIBUTE, LOCAL_USER_ID);
        return true;
    }
}
