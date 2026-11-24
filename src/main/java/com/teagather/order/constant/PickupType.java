package com.teagather.order.constant;

import lombok.Getter;

@Getter
public enum PickupType {
    STORE("매장에서"),
    TAKEOUT("포장");

    private final String label;

    PickupType(String label) {
        this.label = label;
    }
}
