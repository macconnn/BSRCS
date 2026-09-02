package com.baseball.score.service;

import com.baseball.score.entity.GamePitcherStat;
import com.baseball.score.repository.GamePitcherStatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 球員「生涯」投手成績計算。
 *
 * 跟 PlayerStatsService（打擊）的設計理念一致：不維護寫死的累計欄位，
 * 而是把 game_pitcher_stat 這張「每場比賽 × 每位投手」的即時累積表，
 * 依球員 id 全部撈出來加總，每次查詢都是最新結果。
 */
@Service
@RequiredArgsConstructor
public class PitcherStatsService {

    private final GamePitcherStatRepository pitcherStatRepo;

    /** 單一球員的生涯累積投手成績。inningsOuts 為總出局數，局數顯示時再換算成「整局.出局」格式（例如 16 個出局 → 5.1 局）。 */
    public record CareerPitchingStats(
            int gamesPitched, int inningsOuts, int pitches, int runsAllowed,
            int hitsAllowed, int doublesAllowed, int triplesAllowed, int homeRunsAllowed,
            int walksAllowed, int hitByPitchAllowed, int stolenBasesAllowed, BigDecimal era
    ) {
        public static final CareerPitchingStats EMPTY =
                new CareerPitchingStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO);
    }

    /** 是否曾經有投手紀錄（決定球員數據頁要不要多顯示「投手數據」分頁） */
    @Transactional(readOnly = true)
    public boolean hasPitchingRecord(Long playerId) {
        if (playerId == null) return false;
        return !pitcherStatRepo.findByPlayerId(playerId).isEmpty();
    }

    @Transactional(readOnly = true)
    public CareerPitchingStats careerStats(Long playerId) {
        if (playerId == null) return CareerPitchingStats.EMPTY;

        List<GamePitcherStat> rows = pitcherStatRepo.findByPlayerId(playerId);
        if (rows.isEmpty()) return CareerPitchingStats.EMPTY;

        int inningsOuts = 0, pitches = 0, runsAllowed = 0, hitsAllowed = 0,
                doublesAllowed = 0, triplesAllowed = 0, homeRunsAllowed = 0,
                walksAllowed = 0, hitByPitchAllowed = 0, stolenBasesAllowed = 0;

        for (GamePitcherStat r : rows) {
            inningsOuts += r.getInningsOuts();
            pitches += r.getPitches();
            runsAllowed += r.getRunsAllowed();
            hitsAllowed += r.getHitsAllowed();
            doublesAllowed += r.getDoublesAllowed();
            triplesAllowed += r.getTriplesAllowed();
            homeRunsAllowed += r.getHomeRunsAllowed();
            walksAllowed += r.getWalksAllowed();
            hitByPitchAllowed += r.getHitByPitchAllowed();
            stolenBasesAllowed += r.getStolenBasesAllowed();
        }

        BigDecimal era = inningsOuts == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(runsAllowed).multiply(BigDecimal.valueOf(27))
                        .divide(BigDecimal.valueOf(inningsOuts), 2, RoundingMode.HALF_UP);

        return new CareerPitchingStats(rows.size(), inningsOuts, pitches, runsAllowed,
                hitsAllowed, doublesAllowed, triplesAllowed, homeRunsAllowed,
                walksAllowed, hitByPitchAllowed, stolenBasesAllowed, era);
    }

    /** 局數文字，棒球慣例格式：16 個出局 → "5.1"（5 局又 1 個出局，不是小數點五局一） */
    public String inningsText(int inningsOuts) {
        return (inningsOuts / 3) + "." + (inningsOuts % 3);
    }

    /** 防禦率文字，例如 3.86 / 0.00（沒有局數時顯示 0.00） */
    public String eraText(BigDecimal era) {
        BigDecimal v = era == null ? BigDecimal.ZERO : era;
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
