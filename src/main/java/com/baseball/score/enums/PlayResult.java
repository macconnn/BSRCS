package com.baseball.score.enums;

/**
 * 打席結果。
 * bases   = 打者上到幾壘（0 = 未上壘）
 * outs    = 本打席製造幾個出局
 * hit     = 是否計入安打數 (H)
 * error   = 是否計入失誤數 (E)
 * runnerAdvance = 壘上跑者自動推進幾個壘包
 */
public enum PlayResult {
    SINGLE("安打", 1, 0, true, false, 1),
    DOUBLE("二壘安打", 2, 0, true, false, 2),
    TRIPLE("三壘安打", 3, 0, true, false, 3),
    HOME_RUN("全壘打", 4, 0, true, false, 4),
    INFIELD_HIT("內野安打", 1, 0, true, false, 1),

    WALK("四壞球保送", 1, 0, false, false, 0),
    HIT_BY_PITCH("觸身球", 1, 0, false, false, 0),

    SAC_FLY("犧牲打", 0, 1, false, false, 1),
    SAC_BUNT("犧牲觸擊", 0, 1, false, false, 1),
    INFIELD_SAC_FLY("內野高飛犧牲打", 0, 1, false, false, 1),

    STRIKEOUT("三振", 0, 1, false, false, 0),
    INFIELD_GROUND_OUT("內野滾地球", 0, 1, false, false, 0),
    INFIELD_FLY_OUT("內野飛球", 0, 1, false, false, 0),
    OUTFIELD_FLY_OUT("外野飛球", 0, 1, false, false, 0),
    OUTFIELD_GROUND_OUT("外野滾地球", 0, 0, true, false, 1),
    FOUL_OUT("界外球", 0, 1, false, false, 0),
    CAUGHT_STEALING("阻殺", 0, 1, false, false, 0),
    DOUBLE_PLAY("雙殺打", 0, 2, false, false, 0),
    TRIPLE_PLAY("三殺打", 0, 3, false, false, 0),

    ERROR("失誤", 1, 0, false, true, 1),
    OTHER("其他", 0, 0, false, false, 0);

    private final String label;
    private final int bases;
    private final int outs;
    private final boolean hit;
    private final boolean error;
    private final int runnerAdvance;

    PlayResult(String label, int bases, int outs, boolean hit, boolean error, int runnerAdvance) {
        this.label = label; this.bases = bases; this.outs = outs;
        this.hit = hit; this.error = error; this.runnerAdvance = runnerAdvance;
    }
    public String getLabel() { return label; }
    public int getBases() { return bases; }
    public int getOuts() { return outs; }
    public boolean isHit() { return hit; }
    public boolean isError() { return error; }
    public int getRunnerAdvance() { return runnerAdvance; }

    /**
     * 是否計入「打數」(AB)。保送 / 觸身球 / 犧牲打（觸擊、高飛）/ 其他 不計打數，
     * 其餘結果（含安打、出局、失誤）都計入打數。與 ScoringService 記錄打席時的規則一致，
     * 供打擊率動態計算共用，避免規則分散、各處寫死。
     */
    public boolean isCountsAsAtBat() {
        return switch (this) {
            case WALK, HIT_BY_PITCH, SAC_FLY, SAC_BUNT, INFIELD_SAC_FLY, OTHER -> false;
            default -> true;
        };
    }
}
