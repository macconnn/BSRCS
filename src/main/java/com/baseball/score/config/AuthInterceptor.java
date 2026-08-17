package com.baseball.score.config;

import com.baseball.score.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 1. 解析 cookie 中的 token → 放進 request attribute "currentUser"
 * 2. 有 @RequireEditor 的 API，非編輯者直接回 403（不使用 Spring Security）
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER = "currentUser";
    public static final String ATTR_DEVICE = "deviceType";

    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        CurrentUser user = authService.resolve(request);
        request.setAttribute(ATTR_USER, user);

        if (handler instanceof HandlerMethod hm) {
            boolean needEditor = hm.hasMethodAnnotation(RequireEditor.class)
                    || hm.getBeanType().isAnnotationPresent(RequireEditor.class);
            if (needEditor && !user.canEdit()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"success\":false,\"message\":\"僅編輯者可執行此操作，請先登入\"}");
                return false;
            }
        }
        return true;
    }
}
