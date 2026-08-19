package com.baseball.score.dto;

import com.baseball.score.enums.StealOutcome;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 盜壘請求：獨立於打席結果之外的跑者事件，不影響打者的打數與打席。
 * fromBase：出發壘包（1=一壘、2=二壘、3=三壘）。
 * toBase：目標壘包（2=二壘、3=三壘、4=本壘）；outcome=CAUGHT（被阻殺）時可不填。
 * error：這次推進是否伴隨守備失誤（例如盜二壘成功後，因傳球失誤又多跑上三壘）。
 */
@Data
public class StealRequest {
    @NotNull(message = "請選擇出發壘包")
    private Integer fromBase;
    private Integer toBase;
    @NotNull(message = "請選擇盜壘結果")
    private StealOutcome outcome;
    private boolean error;
}
