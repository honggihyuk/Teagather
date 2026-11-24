package com.teagather.analysis.entity;

import com.teagather.analysis.constant.AnalysisStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.LocalDateTime;

// 분석 한 번 (고객 분석 + 메뉴 판매 분석을 함께 돌린다)
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalysisRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AnalysisStatus status;

    @Column(length = 40)
    private String engine;              // "PyTorch 2.2.2+cpu" 또는 "Java 대체 계산"

    @Column(length = 20)
    private String triggeredBy;         // 관리자 아이디 · startup · schedule

    @Column(length = 1000)
    private String message;             // 실패 이유, 대체 계산으로 바뀐 이유

    @Column(length = 500)
    private String lossText;            // 학습 손실 · 정확도 요약

    private int customerCount;
    private int orderCount;

    public boolean isTorch() {
        return engine != null && engine.startsWith("PyTorch");
    }

    public String getTriggerLabel() {
        if ("startup".equals(triggeredBy)) return "서버 시작";
        if ("schedule".equals(triggeredBy)) return "매일 자동";
        return triggeredBy;
    }

    public String getElapsedText() {
        if (startedAt == null || finishedAt == null) return "";
        long s = Duration.between(startedAt, finishedAt).toSeconds();
        return s < 60 ? s + "초" : (s / 60) + "분 " + (s % 60) + "초";
    }
}
