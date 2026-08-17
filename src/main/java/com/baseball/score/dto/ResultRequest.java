package com.baseball.score.dto;

import com.baseball.score.enums.PlayResult;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ResultRequest {
    @NotNull(message = "請選擇打席結果")
    private PlayResult result;
}
