package com.teagather.product.entity;

import com.teagather.product.constant.DiscountSource;
import com.teagather.product.constant.DiscountType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 메뉴 할인 (기간 · 정률/정액)
@Entity
@Getter
@Setter
@ToString(exclude = "product")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Discount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String name;                // 예: 오늘의 할인, 겨울 시즌 할인

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private DiscountType type;

    @Column(name = "discount_value")      // value 는 H2·MySQL 예약어
    private int value;                  // 정률이면 %, 정액이면 원

    private LocalDate startDate;
    private LocalDate endDate;

    @Builder.Default
    private boolean active = true;      // 사용 중지하면 false

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private DiscountSource source;

    private LocalDateTime created;

    // 가격 하나에 대한 할인 금액
    public int amountFor(int price) {
        int a = (type == DiscountType.RATE) ? (int) Math.round(price * value / 100.0 / 10) * 10 : value;
        return Math.max(0, Math.min(a, price));
    }

    public boolean isOn(LocalDate d) {
        return active && !d.isBefore(startDate) && !d.isAfter(endDate);
    }

    public String getValueText() {
        return type == DiscountType.RATE ? value + "%" : String.format("%,d원", value);
    }

    // 상태 (계산) : 진행 중 · 예정 · 종료 · 중지
    public String stateCode(LocalDate today) {
        if (!active) return "PAUSED";
        if (today.isBefore(startDate)) return "SCHEDULED";
        if (today.isAfter(endDate)) return "ENDED";
        return "ACTIVE";
    }

    public String stateLabel(LocalDate today) {
        return switch (stateCode(today)) {
            case "PAUSED" -> "중지";
            case "SCHEDULED" -> "예정";
            case "ENDED" -> "종료";
            default -> "진행 중";
        };
    }
}
