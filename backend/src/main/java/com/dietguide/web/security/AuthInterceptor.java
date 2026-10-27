package com.dietguide.web.security;

import com.dietguide.shared.AppException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID_ATTRIBUTE = "dietguide.userId";

    private final JwtService jwtService;

    public AuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw AppException.unauthorized("missing token");
        }

        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            throw AppException.unauthorized("missing token");
        }

        request.setAttribute(USER_ID_ATTRIBUTE, jwtService.verify(token));
        return true;
    }
}
