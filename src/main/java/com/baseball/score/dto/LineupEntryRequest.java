package com.baseball.score.dto;

import lombok.Data;

/** 建立比賽時，編輯者手動安排的單一打線項目（某位球員 + 棒次 + 守備位置） */
@Data
public class LineupEntryRequest {
    /** 球員 id（須屬於對應球隊） */
    private Long playerId;
    /** 棒次 1~N，同一隊不可重複 */
    private Integer battingOrder;
    /** 守備位置，例如 投手 / 捕手 / 一壘手…；未填則沿用球員的慣用守備位置 */
    private String position;
}
