package com.teagather.order.constant;

import lombok.Getter;

// 주문 상태 : 접수 → 준비 중 → 픽업 대기 → 완료 (취소는 완료 전까지)
@Getter
public enum OrderStatus {
    PLACED("주문 접수", "cream", 1),
    PREPARING("준비 중", "cream", 2),
    READY("픽업 대기", "solid", 3),
    COMPLETED("완료", "grey", 4),
    CANCELED("취소", "red", 0);

    private final String label;
    private final String chip;
    private final int step;         // 진행 막대 단계 (취소 0)

    OrderStatus(String label, String chip, int step) {
        this.label = label;
        this.chip = chip;
        this.step = step;
    }

    public OrderStatus next() {
        return switch (this) {
            case PLACED -> PREPARING;
            case PREPARING -> READY;
            case READY -> COMPLETED;
            default -> null;
        };
    }

    public boolean isOpen() {
        return this == PLACED || this == PREPARING || this == READY;
    }
}
