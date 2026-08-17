package com.baseball.score.enums;

/** 球數紀錄按鈕：好球 (S)、壞球 (B)、界外球 */
public enum PitchCall {
    STRIKE("好球"), BALL("壞球"), FOUL("界外球");
    private final String label;
    PitchCall(String label) { this.label = label; }
    public String getLabel() { return label; }
}
