package com.teagather.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

// 매출 리포트
@Getter
@Builder
public class SalesReport {

    private final LocalDate from;
    private final LocalDate to;
    private final long revenue;         // 결제 금액 합계
    private final int orders;
    private final long avgBasket;
    private final long discount;        // 할인 금액 합계
    private final List<Bar> daily;
    private final List<Bar> hourly;
    private final List<Bar> byCategory;
    private final List<ProductRow> byProduct;

    @Getter
    @AllArgsConstructor
    public static class ProductRow {
        private final Long productId;
        private final String name;
        private final String category;
        private final int qty;
        private final long revenue;
        private final double share;         // 매출 비중 %
        private final double discountShare; // 할인 판매 비율 %
    }
}
