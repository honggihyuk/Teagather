package com.teagather.analysis.constant;

import lombok.Getter;

@Getter
public enum AnalysisStatus {
    RUNNING("분석 중", "cream"),
    SUCCESS("완료", "solid"),
    FAILED("실패", "red");

    private final String label;
    private final String chip;

    AnalysisStatus(String label, String chip) {
        this.label = label;
        this.chip = chip;
    }
}
