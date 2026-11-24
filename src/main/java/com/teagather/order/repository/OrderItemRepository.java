package com.teagather.order.repository;

import com.teagather.order.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    boolean existsByProductId(Long productId);

    // 기간 안 판매 품목 (취소 제외) : [주문 id, 고객 id, 주문 시각, 상품 id, 수량, 줄 금액, 할인]
    @Query("select o.id, m.id, o.orderedAt, p.id, i.quantity, i.lineAmount, i.discountAmount "
            + "from OrderItem i join i.order o left join o.member m join i.product p "
            + "where o.orderedAt >= :from and o.orderedAt < :to and o.status <> com.teagather.order.constant.OrderStatus.CANCELED")
    List<Object[]> findSalesRows(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // 고객 한 명이 자주 사는 메뉴 : [상품 id, 이름, 수량 합]
    @Query("select p.id, p.name, sum(i.quantity) from OrderItem i join i.order o join i.product p "
            + "where o.member.id = :memberId and o.status <> com.teagather.order.constant.OrderStatus.CANCELED "
            + "group by p.id, p.name order by sum(i.quantity) desc")
    List<Object[]> findFavorites(@Param("memberId") Long memberId);
}
