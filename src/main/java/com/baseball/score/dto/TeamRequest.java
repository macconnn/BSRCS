package com.baseball.score.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TeamRequest {
    @NotBlank(message = "請輸入球隊名稱")
    private String name;
    private String shortName;
    /** 顯示色，例如 #1d4ed8 */
    private String colorHex;
}
