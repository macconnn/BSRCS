package com.baseball.score.dto;

import com.baseball.score.enums.TeamSide;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 手動編輯「某一局」的得分：用來修正手誤造成的比分錯誤。
 * 更新後，該隊的總分會自動重新計算成「每一局分數的加總」，確保總分跟每局分數永遠對得起來。
 */
@Data
public class ScoreEditRequest {
    @NotNull(message = "請選擇隊伍")
    private TeamSide side;

    @NotNull(message = "請選擇局數")
    @Min(value = 1, message = "局數必須大於 0")
    private Integer inning;

    @NotNull(message = "請輸入該局得分")
    @Min(value = 0, message = "分數不可小於 0")
    private Integer runs;
}

