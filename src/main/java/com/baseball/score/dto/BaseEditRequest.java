package com.baseball.score.dto;

import lombok.Data;

/**
 * 手動編輯壘包狀態：用於場上發生系統沒有對應規則可以描述的特殊狀況時，
 * 讓記錄員直接指定三個壘包目前各是哪一位球員（game_lineup.id），null 表示該壘包無人。
 * 每次請求會整組覆蓋壘包狀態，而不是單一壘包的差異更新。
 */
@Data
public class BaseEditRequest {
    private Long runnerFirst;
    private Long runnerSecond;
    private Long runnerThird;
}
