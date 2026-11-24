package com.teagather.product.entity;

import jakarta.persistence.*;
import lombok.*;

// 옵션 하나 (예: ICE, 라지 +500원)
@Entity
@Getter
@Setter
@ToString(exclude = "group")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private OptionGroup group;

    @Column(nullable = false, length = 20)
    private String name;

    private int extraPrice;             // 추가 금액 (원)
    private int sortOrder;
}
