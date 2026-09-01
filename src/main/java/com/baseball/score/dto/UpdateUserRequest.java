package com.baseball.score.dto;

import com.baseball.score.enums.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 僅 ADMIN 可用：編輯某個帳號的顯示名稱／Email／角色 */
@Data
public class UpdateUserRequest {
    @NotBlank(message = "請輸入顯示名稱")
    private String displayName;

    @NotBlank(message = "請輸入 Email")
    private String email;

    @NotNull(message = "請選擇角色")
    private Role role;
}
