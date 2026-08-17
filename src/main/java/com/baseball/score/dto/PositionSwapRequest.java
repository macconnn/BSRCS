package com.baseball.score.dto;

import com.baseball.score.enums.TeamSide;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 比賽進行中，單純互換兩位場上球員的守備位置（不涉及換人、不影響打線與棒次） */
@Data
public class PositionSwapRequest {
    @NotNull(message = "請選擇球隊")
    private TeamSide side;
    /** 球員 A 的打線項目 id（game_lineup.id） */
    @NotNull(message = "請選擇球員 A")
    private Long lineupIdA;
    /** 球員 B 的打線項目 id（game_lineup.id） */
    @NotNull(message = "請選擇球員 B")
    private Long lineupIdB;
}
