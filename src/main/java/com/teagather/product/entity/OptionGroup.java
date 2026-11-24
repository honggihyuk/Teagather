package com.teagather.product.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

// 옵션 그룹 (온도, 사이즈, 샷 추가, 시럽, 당도 …)
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptionGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String name;

    private boolean required;           // 꼭 하나 골라야 하나
    private boolean multiple;           // 여러 개 고를 수 있나 (샷 추가 등)
    private int sortOrder;

    @Builder.Default
    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder asc, id asc")
    private List<OptionItem> items = new ArrayList<>();

    public String getRuleText() {
        return (required ? "필수" : "선택") + " · " + (multiple ? "여러 개" : "하나만");
    }
}
