package com.baseball.score.dto;

import com.baseball.score.enums.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 僅 ADMIN 可用：新增一個帳號（Email／顯示名稱／角色都必填） */
@Data
public class CreateUserRequest {
    @NotBlank(message = "請輸入 Email")
    private String email;

    @NotBlank(message = "請輸入顯示名稱")
    private String displayName;

    @NotNull(message = "請選擇角色")
    private Role role;
}
