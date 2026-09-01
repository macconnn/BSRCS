package com.baseball.score.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 僅 ADMIN 可用：啟用／停用某個帳號 */
@Data
public class UpdateUserEnabledRequest {
    @NotNull(message = "請指定啟用或停用")
    private Boolean enabled;
}
