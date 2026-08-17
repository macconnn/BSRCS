package com.baseball.score.dto;

import com.baseball.score.enums.PitchCall;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PitchRequest {
    /** STRIKE / BALL / FOUL */
    @NotNull(message = "請選擇球種判定")
    private PitchCall call;
    /** 直球 / 曲球 / 滑球 / 變速球 */
    private String pitchType;
    private Integer speedKmh;
}
