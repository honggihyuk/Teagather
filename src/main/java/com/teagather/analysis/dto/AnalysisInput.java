package com.teagather.analysis.dto;

import java.util.List;

// 분석 입력 (analytics/work/input.json). 날짜는 yyyy-MM-dd 문자열
public record AnalysisInput(
        String today,
        List<Cat> categories,
        List<Prod> products,
        List<Cust> customers,
        List<Ord> orders,
        List<Disc> discounts) {

    public record Cat(long id, String name) {}

    public record Prod(long id, String name, long categoryId, String category, int price, int cost,
                       List<String> tags, String status, boolean discountedNow, String launchedAt) {}

    public record Cust(long id, String created) {}

    public record Ord(long id, long customerId, String date, int hour, List<Line> items) {}

    public record Line(long productId, int qty, long amount, boolean discounted) {}

    public record Disc(long productId, String start, String end) {}
}
