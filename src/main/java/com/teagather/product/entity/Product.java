package com.teagather.product.entity;

import com.teagather.product.constant.ProductStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// 메뉴 (상품)
@Entity
@Getter
@Setter
@ToString(exclude = {"category", "optionGroups"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    private Category category;

    private int price;                  // 판매가 (원)
    private int cost;                   // 원가 (원) — 마진율 계산, 할인 여유 판단

    @Column(length = 300)
    private String description;

    @Column(length = 200)
    private String tags;                // 쉼표 구분 : 우유,달콤,시즌 … (분석의 메뉴 속성)

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ProductStatus status;

    private boolean todayMenu;          // 오늘의 메뉴
    private boolean newMenu;            // 신메뉴 표시
    private LocalDate launchedAt;       // 출시일

    private int sortOrder;

    @Builder.Default
    @ManyToMany
    @JoinTable(name = "product_option_group",
            joinColumns = @JoinColumn(name = "product_id"),
            inverseJoinColumns = @JoinColumn(name = "option_group_id"))
    @OrderBy("sortOrder asc")
    private Set<OptionGroup> optionGroups = new LinkedHashSet<>();

    private LocalDateTime created;

    public List<String> getTagList() {
        if (tags == null || tags.isBlank()) return List.of();
        return Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    // 마진율 (0~1)
    public double getMarginRate() {
        return price <= 0 ? 0 : (price - cost) / (double) price;
    }

    public int getMarginPercent() {
        return (int) Math.round(getMarginRate() * 100);
    }

    public boolean isOrderable() {
        return status == ProductStatus.ON_SALE;
    }

    // 신메뉴 표시 : 표시가 켜져 있고 출시 30일 안
    public boolean isFresh() {
        return newMenu && (launchedAt == null || !launchedAt.isBefore(LocalDate.now().minusDays(30)));
    }
}
