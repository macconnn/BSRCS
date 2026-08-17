package com.baseball.score.service;

import com.baseball.score.enums.PlayResult;
import com.baseball.score.repository.AtBatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 球員「生涯」打擊成績計算。
 *
 * 打擊率不再是 player.batting_avg 這種手動輸入、寫死的欄位，
 * 而是每次需要顯示時，即時掃描 at_bat 這張「所有打擊紀錄」的表，
 * 依紀錄員在每個打席記下的結果（安打 / 三振 / 保送 ... 等）動態加總、即時計算出來。
 * 因此紀錄員每記一次打席，這裡算出的打擊率就會立刻反映最新狀況，不需要另外維護、更新欄位。
 */
@Service
@RequiredArgsConstructor
public class PlayerStatsService {

    private final AtBatRepository atBatRepo;

    /** 單一球員的生涯累積打擊成績（打數 / 安打數 / 打擊率）。 */
    public record CareerStats(int atBats, int hits, BigDecimal avg) {
        public static final CareerStats EMPTY = new CareerStats(0, 0, BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public CareerStats careerStats(Long playerId) {
        if (playerId == null) return CareerStats.EMPTY;

        List<PlayResult> results = atBatRepo.findResultsByPlayerId(playerId);
        if (results.isEmpty()) return CareerStats.EMPTY;

        int atBats = 0;
        int hits = 0;
        for (PlayResult r : results) {
            if (r.isCountsAsAtBat()) atBats++;
            if (r.isHit()) hits++;
        }

        BigDecimal avg = atBats == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(hits).divide(BigDecimal.valueOf(atBats), 3, RoundingMode.HALF_UP);

        return new CareerStats(atBats, hits, avg);
    }

    /** 打擊率文字，例如 .333 / .000（沒有任何打席紀錄時，一律顯示、預設為 0）。 */
    public String avgText(BigDecimal avg) {
        BigDecimal v = avg == null ? BigDecimal.ZERO : avg;
        String s = v.setScale(3, RoundingMode.HALF_UP).toPlainString();
        return s.startsWith("0") ? s.substring(1) : s;
    }
}
