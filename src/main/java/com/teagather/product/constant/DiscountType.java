package com.teagather.product.constant;

import lombok.Getter;

@Getter
public enum DiscountType {
    RATE("정률(%)"),
    AMOUNT("정액(원)");

    private final String label;

    DiscountType(String label) {
        this.label = label;
    }
}
