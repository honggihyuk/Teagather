package com.teagather.order.entity;

import com.teagather.product.entity.Product;
import jakarta.persistence.*;
import lombok.*;

// 주문 품목 (주문 당시 이름·가격을 그대로 남긴다)
@Entity
@Getter
@Setter
@ToString(exclude = {"order", "product"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY)
    private Product product;

    @Column(length = 40)
    private String productName;

    @Column(length = 120)
    private String optionText;          // "ICE · 라지 · 샷 추가"

    private int unitPrice;              // 메뉴 가격 + 옵션 추가금
    private int quantity;
    private int discountAmount;         // 이 줄의 할인 합계
    private int lineAmount;             // unitPrice × quantity − discountAmount
}
