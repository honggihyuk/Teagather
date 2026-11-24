package com.teagather.product.entity;

import jakarta.persistence.*;
import lombok.*;

// 메뉴 카테고리 (커피, 티, 라떼·논커피, 에이드·주스, 디저트, 베이커리 …)
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 20)
    private String name;

    private int sortOrder;              // 작은 숫자가 앞

    @Column(length = 20)
    private String tone;                // 메뉴 이미지 타일 색 : brown, green, cream, orange, pink, yellow

    @Column(length = 20)
    private String icon;                // 타일 아이콘 : cup, tea, latte, ade, cake, bread

    public String getToneOrDefault() {
        return tone == null ? "cream" : tone;
    }

    public String getIconOrDefault() {
        return icon == null ? "cup" : icon;
    }
}
