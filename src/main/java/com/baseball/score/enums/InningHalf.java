package com.baseball.score.enums;

public enum InningHalf {
    TOP("上"), BOTTOM("下");
    private final String label;
    InningHalf(String label) { this.label = label; }
    public String getLabel() { return label; }
}
