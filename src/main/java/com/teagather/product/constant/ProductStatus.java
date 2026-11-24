package com.teagather.product.constant;

import lombok.Getter;

// 상품 상태
@Getter
public enum ProductStatus {
    ON_SALE("판매 중", "solid"),        // 고객 화면에 보이고 주문 가능
    SOLD_OUT("품절", "red"),            // 보이지만 주문 불가
    HIDDEN("숨김", "grey"),             // 고객 화면에 안 보임
    CANDIDATE("신메뉴 후보", "cream");  // 아직 출시 전 — 분석에서 출시 후보로 평가

    private final String label;
    private final String chip;          // 상태 칩 색 (fragments/ui :: chip)

    ProductStatus(String label, String chip) {
        this.label = label;
        this.chip = chip;
    }

    public boolean isVisible() {
        return this == ON_SALE || this == SOLD_OUT;
    }
}
