package com.baseball.score.controller;

import com.baseball.score.config.AuthInterceptor;
import com.baseball.score.config.CurrentUser;
import com.baseball.score.dto.ApiResponse;
import com.baseball.score.dto.OtpRequest;
import com.baseball.score.dto.OtpVerifyRequest;
import com.baseball.score.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthApiController {

    private final AuthService authService;

    /** 發送驗證碼（JavaMail） */
    @PostMapping("/otp")
    public ApiResponse<Map<String, Object>> requestOtp(@Valid @RequestBody OtpRequest req, HttpServletRequest request) {
        int ttl = authService.requestOtp(req.getEmail(), authService.clientIp(request));
        return ApiResponse.ok("驗證碼已寄送至 " + req.getEmail(), Map.of("ttlMinutes", ttl));
    }

    /** 驗證並登入 */
    @PostMapping("/verify")
    public ApiResponse<Map<String, Object>> verify(@Valid @RequestBody OtpVerifyRequest req,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) {
        String token = authService.verifyOtp(req.getEmail(), req.getCode(), request.getHeader("User-Agent"));
        authService.writeCookie(response, token, authService.cookieMaxAge());
        return ApiResponse.ok("登入成功", Map.of("redirect", "/games"));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(request, response);
        return ApiResponse.ok("已登出", null);
    }

    /** 前端用來判斷要顯示編輯或檢視模式 */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest request) {
        CurrentUser user = (CurrentUser) request.getAttribute(AuthInterceptor.ATTR_USER);
        if (user == null) user = CurrentUser.viewer();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loggedIn", user.isLoggedIn());
        m.put("email", user.getEmail());
        m.put("displayName", user.getDisplayName());
        m.put("role", user.getRole().name());
        m.put("canEdit", user.canEdit());
        return ApiResponse.ok(m);
    }
}
