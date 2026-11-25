package com.teagather.order.repository;

import com.teagather.order.constant.OrderStatus;
import com.teagather.order.entity.CustomerOrder;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    List<CustomerOrder> findByMemberIdOrderByOrderedAtDesc(Long memberId, Pageable pageable);

    // 관리자 주문 목록 (상태 필터, 최신순)
    @Query("select o from CustomerOrder o left join fetch o.member where (:status is null or o.status = :status) order by o.orderedAt desc")
    List<CustomerOrder> findForAdmin(@Param("status") OrderStatus status, Pageable pageable);

    long countByStatus(OrderStatus status);

    boolean existsByOrderNo(String orderNo);

    // 그날 마지막 주문번호 (카운터 줄을 처음 만들 때 기존 주문과 겹치지 않게)
    //  번호는 %04d 라 10000 번부터 다섯 자리가 된다 → 문자열 max 로는 "9999" 가 "10000" 보다 커 보인다
    //  그래서 길이가 긴 것 먼저, 같은 길이 안에서는 큰 값 먼저로 정렬해 첫 줄을 쓴다 (H2·MySQL 공통 JPQL)
    @Query("select o.orderNo from CustomerOrder o where o.orderNo like concat(:prefix, '-%') "
            + "order by length(o.orderNo) desc, o.orderNo desc")
    List<String> findOrderNosLatestFirst(@Param("prefix") String prefix, Pageable pageable);

    default String findMaxOrderNo(String prefix) {
        List<String> top = findOrderNosLatestFirst(prefix, PageRequest.of(0, 1));
        return top.isEmpty() ? null : top.get(0);
    }

    long countByStatusIn(Collection<OrderStatus> statuses);

    // 기간 안 주문 (취소 제외) : 매출 리포트, 대시보드
    @Query("select o from CustomerOrder o where o.orderedAt >= :from and o.orderedAt < :to and o.status <> com.teagather.order.constant.OrderStatus.CANCELED")
    List<CustomerOrder> findValidBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
