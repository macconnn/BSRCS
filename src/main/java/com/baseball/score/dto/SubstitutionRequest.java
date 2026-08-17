package com.baseball.score.dto;

import com.baseball.score.enums.TeamSide;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 比賽進行中換人（替補上場）請求 */
@Data
public class SubstitutionRequest {
    @NotNull(message = "請選擇球隊")
    private TeamSide side;
    /** 被換下場的打線項目 id（game_lineup.id） */
    @NotNull(message = "請選擇要替換下場的球員")
    private Long outLineupId;
    /** 換上場的球員 id（player.id，須為該隊尚未在場上的球員） */
    @NotNull(message = "請選擇要換上場的球員")
    private Long inPlayerId;
    /** 守備位置；未填則沿用被換下球員原本的守備位置 */
    private String position;
}
