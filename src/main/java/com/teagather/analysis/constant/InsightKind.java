package com.teagather.analysis.constant;

import lombok.Getter;

// 메뉴 판매 분석이 고르는 세 가지
@Getter
public enum InsightKind {
    DISCOUNT("할인 추천", "판매가 줄었지만 마진에 여유가 있는 메뉴", "할인 적용"),
    TODAY("오늘의 메뉴 추천", "오늘 많이 팔릴 것으로 예상되는 메뉴", "오늘의 메뉴로"),
    NEW("신메뉴 후보", "고객 취향과 잘 맞을 것으로 예상되는 출시 전 메뉴", "출시하기");

    private final String label;
    private final String description;
    private final String action;

    InsightKind(String label, String description, String action) {
        this.label = label;
        this.description = description;
        this.action = action;
    }
}
