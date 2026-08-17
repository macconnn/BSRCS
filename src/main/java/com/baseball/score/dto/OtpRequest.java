package com.baseball.score.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OtpRequest {
    @NotBlank(message = "請輸入 Email")
    private String email;
}
