package com.teagather.analysis.entity;

import com.teagather.member.entity.Member;
import com.teagather.product.entity.Product;
import jakarta.persistence.*;
import lombok.*;

// 고객별 추천 메뉴 (가장 최근 분석 결과만 남긴다)
@Entity
@Table(indexes = @Index(name = "idx_reco_member", columnList = "member_id"))
@Getter
@Setter
@ToString(exclude = {"run", "member", "product"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private AnalysisRun run;

    @ManyToOne(fetch = FetchType.LAZY)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    @Column(name = "reco_rank")
    private int rank;
    private double score;

    @Column(length = 100)
    private String reason;
}
