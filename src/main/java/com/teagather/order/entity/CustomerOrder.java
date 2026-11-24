package com.teagather.order.entity;

import com.teagather.member.entity.Member;
import com.teagather.order.constant.OrderStatus;
import com.teagather.order.constant.PickupType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 주문 (테이블 이름 orders — order 는 SQL 예약어)
@Entity
@Table(name = "orders", indexes = @Index(name = "idx_orders_ordered_at", columnList = "orderedAt"))
@Getter
@Setter
@ToString(exclude = {"member", "items"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 20)
    private String orderNo;             // 251124-0012

    @ManyToOne(fetch = FetchType.LAZY)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private PickupType pickupType;

    @Column(length = 200)
    private String request;             // 요청사항

    private LocalDateTime orderedAt;

    private int totalAmount;            // 할인 전 금액
    private int discountAmount;         // 할인 금액
    private int payAmount;              // 결제 금액
    private int earnedPoint;            // 적립 포인트

    @Builder.Default
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id asc")
    private List<OrderItem> items = new ArrayList<>();

    public void addItem(OrderItem item) {
        item.setOrder(this);
        items.add(item);
    }

    // "아메리카노 외 2건"
    public String getSummary() {
        if (items.isEmpty()) return "";
        String first = items.get(0).getProductName();
        int qty = items.stream().mapToInt(OrderItem::getQuantity).sum();
        return items.size() == 1 ? first + (qty > 1 ? " " + qty + "개" : "") : first + " 외 " + (items.size() - 1) + "건";
    }

    public int getItemCount() {
        return items.stream().mapToInt(OrderItem::getQuantity).sum();
    }
}
