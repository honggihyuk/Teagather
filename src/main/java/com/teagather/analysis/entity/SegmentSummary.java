package com.teagather.analysis.entity;

import jakarta.persistence.*;
import lombok.*;

// 고객군 하나의 요약 (분석 한 번마다)
@Entity
@Getter
@Setter
@ToString(exclude = "run")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SegmentSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private AnalysisRun run;

    @Column(length = 20)
    private String code;                // VIP · LOYAL · NEW · OCCASIONAL · AT_RISK
    @Column(length = 20)
    private String label;

    private int customerCount;
    private double avgOrders;           // 최근 90일 평균 주문 수
    private long avgSpent;              // 최근 90일 평균 결제 금액
    private double avgRecency;          // 마지막 주문 뒤 평균 경과일

    @Column(length = 20)
    private String topCategory;         // 가장 많이 산 카테고리
    @Column(length = 100)
    private String action;              // 추천 액션
    @Column(length = 200)
    private String description;

    private int sortOrder;

    // 고객군 칩 색
    public String getChip() {
        return switch (code == null ? "" : code) {
            case "VIP" -> "solid";
            case "AT_RISK" -> "red";
            case "NEW" -> "outline";
            case "OCCASIONAL" -> "grey";
            default -> "cream";
        };
    }
}
