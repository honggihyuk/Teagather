package com.teagather.analysis.entity;

import com.teagather.analysis.constant.InsightKind;
import com.teagather.product.entity.Product;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// 메뉴 판매 분석이 고른 메뉴 하나 (할인 / 오늘의 메뉴 / 신메뉴 후보)
@Entity
@Getter
@Setter
@ToString(exclude = {"run", "product"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MenuInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private AnalysisRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private InsightKind kind;

    @Column(name = "pick_rank")
    private int rank;                   // 1, 2, 3
    private double score;               // 0~1
    private double forecast;            // 예상 판매 수
    private Integer suggestedRate;      // 할인 추천 : 제안 할인율 (%)

    @Column(length = 300)
    private String reason;

    private boolean applied;
    private LocalDateTime appliedAt;

    public int getScorePercent() {
        return (int) Math.round(Math.max(0, Math.min(1, score)) * 100);
    }
}
