package com.teagather.product.constant;

import lombok.Getter;

// 할인을 누가 만들었나 : 관리자가 직접 / 메뉴 분석 추천을 적용
@Getter
public enum DiscountSource {
    MANUAL("직접"),
    AI("AI 추천");

    private final String label;

    DiscountSource(String label) {
        this.label = label;
    }
}
