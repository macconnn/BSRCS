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

    // 2024 需求：守備位置一律鎖死沿用「被換下（outLineupId）」球員當下在場上守的位置，
    // 不再開放呼叫端指定，也完全不會去看換上球員（inPlayerId）在球隊管理裡登記的守備位置。
    // 因此這裡故意不提供 position 欄位，避免任何人（前端或直接呼叫 API）繞過鎖定規則。
}
