package com.baseball.score.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 加碼失誤推進：用於安打／出局的打席結果已經照正常規則推進壘包之後，
 * 因為守備失誤讓某位跑者又多推進了一個以上的壘包（例如二壘安打，外野傳球失誤讓跑者從二壘多跑上三壘）。
 * fromBase：跑者目前所在壘包（1=一壘、2=二壘、3=三壘）。
 * toBase：多推進到的壘包（2=二壘、3=三壘、4=本壘）。
 * 這個動作只會多算一次球隊失誤、視情況加分，不會被計為安打，也不會算打者的打點（符合真實記錄規則：
 * 因為守備失誤多跑回來的分不算打點）。
 */
@Data
public class ErrorAdvanceRequest {
    @NotNull(message = "請選擇跑者目前所在壘包")
    @Min(value = 1, message = "壘包錯誤")
    private Integer fromBase;

    @NotNull(message = "請選擇要多推進到哪個壘包")
    private Integer toBase;
}
