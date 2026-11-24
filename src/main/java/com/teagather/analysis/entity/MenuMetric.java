package com.teagather.analysis.entity;

import com.teagather.product.entity.Product;
import jakarta.persistence.*;
import lombok.*;

// 메뉴별 지표 (분석 한 번마다)
@Entity
@Getter
@Setter
@ToString(exclude = {"run", "product"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private AnalysisRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    private int qty30;                  // 최근 30일 판매 수
    private long revenue30;             // 최근 30일 매출
    private double trendPct;            // 최근 2주 vs 그 전 2주 (%)
    private double forecast;            // 오늘 예상 판매 수 (MLP)
    private double marginRate;          // 마진율 (0~1)
    private double affinity;            // 고객 선호도 (0~1, 행렬 분해)

    public int getAffinityPercent() {
        return (int) Math.round(affinity * 100);
    }
}
