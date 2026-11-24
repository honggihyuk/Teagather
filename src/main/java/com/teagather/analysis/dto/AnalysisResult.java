package com.teagather.analysis.dto;

import java.util.List;

// 분석 결과 (analytics/teagather_analysis.py 의 output.json 과 같은 모양, Java 대체 계산도 이 모양으로 만든다)
public record AnalysisResult(
        String engine,
        String lossText,
        List<Segment> segments,
        List<CustomerOut> customers,
        List<RecOut> recommendations,
        List<Metric> metrics,
        List<Pick> discount,
        List<Pick> today,
        List<Pick> newMenu) {

    public record Segment(String code, String label, int count, double avgOrders, double avgSpent,
                          double avgRecency, String topCategory, String action, String description) {}

    public record CustomerOut(long id, String segment, String label, Double churnRisk) {}

    public record RecOut(long customerId, long productId, int rank, double score, String reason) {}

    public record Metric(long productId, int qty30, long revenue30, double trendPct, double forecast,
                         double marginRate, double affinity) {}

    public record Pick(long productId, int rank, double score, double forecast, Integer suggestedRate, String reason) {}
}
