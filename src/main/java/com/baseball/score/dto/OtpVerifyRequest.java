package com.baseball.score.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OtpVerifyRequest {
    @NotBlank(message = "請輸入 Email")
    private String email;
    @NotBlank(message = "請輸入驗證碼")
    private String code;
}
