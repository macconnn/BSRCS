package com.baseball.score.controller;

import com.baseball.score.config.AuthInterceptor;
import com.baseball.score.config.CurrentUser;
import com.baseball.score.config.RequireAdmin;
import com.baseball.score.dto.ApiResponse;
import com.baseball.score.dto.CreateUserRequest;
import com.baseball.score.dto.UpdateUserEnabledRequest;
import com.baseball.score.dto.UpdateUserRequest;
import com.baseball.score.entity.AppUser;
import com.baseball.score.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 帳號管理 API：僅 ADMIN 可用（見 @RequireAdmin，由 AuthInterceptor 統一擋非管理員）。 */
@RestController
@RequestMapping("/api/admin/users")
@RequireAdmin
@RequiredArgsConstructor
public class UserApiController {

    private final UserService userService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(userService.listUsers());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateUserRequest req) {
        AppUser user = userService.createUser(req.getEmail(), req.getDisplayName(), req.getRole());
        return ApiResponse.ok("已新增帳號 " + user.getEmail(), Map.of("id", user.getId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                    @Valid @RequestBody UpdateUserRequest req,
                                                    HttpServletRequest request) {
        AppUser user = userService.updateUser(id, req.getDisplayName(), req.getEmail(), req.getRole(), currentUser(request).getUserId());
        return ApiResponse.ok("已更新帳號", Map.of("id", user.getId()));
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<Map<String, Object>> setEnabled(@PathVariable Long id,
                                                        @Valid @RequestBody UpdateUserEnabledRequest req,
                                                        HttpServletRequest request) {
        AppUser user = userService.setEnabled(id, req.getEnabled(), currentUser(request).getUserId());
        String msg = Boolean.TRUE.equals(req.getEnabled()) ? "已啟用帳號" : "已停用帳號";
        return ApiResponse.ok(msg, Map.of("id", user.getId(), "enabled", user.getEnabled()));
    }

    private CurrentUser currentUser(HttpServletRequest request) {
        Object attr = request.getAttribute(AuthInterceptor.ATTR_USER);
        return attr instanceof CurrentUser cu ? cu : CurrentUser.viewer();
    }
}
