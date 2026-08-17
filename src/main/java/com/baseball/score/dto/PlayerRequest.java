package com.baseball.score.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PlayerRequest {
    @NotBlank(message = "請輸入球員姓名")
    private String name;
    /** 背號 */
    private String jerseyNumber;
    /** 守備位置 */
    private String defaultPosition;
    // 打擊率不再由此手動輸入：新球員一律從 0 開始，之後依實際打擊紀錄動態計算。
}
