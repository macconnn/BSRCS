package com.baseball.score.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;

@Data
public class CreateGameRequest {
    @NotBlank(message = "請輸入比賽名稱")
    private String name;
    private LocalDate gameDate;
    private String venue;
    private String remark;
    @NotNull(message = "請選擇客隊")
    private Long awayTeamId;
    @NotNull(message = "請選擇主隊")
    private Long homeTeamId;
    private Integer totalInnings;
}
