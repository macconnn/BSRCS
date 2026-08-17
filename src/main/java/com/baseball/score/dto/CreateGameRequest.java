package com.baseball.score.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;

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

    /**
     * 編輯者手動安排的客隊打線（棒次 + 守備位置）；
     * 未提供或為空時，沿用原本「依球員預設守備位置自動排出 1~9 棒」的規則。
     */
    private List<LineupEntryRequest> awayLineup;

    /** 編輯者手動安排的主隊打線，規則同 awayLineup */
    private List<LineupEntryRequest> homeLineup;
}
