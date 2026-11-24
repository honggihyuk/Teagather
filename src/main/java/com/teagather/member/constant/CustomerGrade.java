package com.teagather.member.constant;

import lombok.Getter;

// 고객 등급 : 누적 결제 금액으로 정한다
@Getter
public enum CustomerGrade {
    BASIC("일반", 0),
    SILVER("실버", 50_000),
    GOLD("골드", 150_000),
    VIP("VIP", 300_000);

    private final String label;
    private final long minSpent;    // 이 금액 이상이면 이 등급

    CustomerGrade(String label, long minSpent) {
        this.label = label;
        this.minSpent = minSpent;
    }

    public static CustomerGrade of(long totalSpent) {
        CustomerGrade g = BASIC;
        for (CustomerGrade c : values()) {
            if (totalSpent >= c.minSpent) g = c;
        }
        return g;
    }
}
