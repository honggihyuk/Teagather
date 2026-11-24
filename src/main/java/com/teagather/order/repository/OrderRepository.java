package com.teagather.order.repository;

import com.teagather.order.constant.OrderStatus;
import com.teagather.order.entity.CustomerOrder;
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

    long countByStatusIn(Collection<OrderStatus> statuses);

    // 기간 안 주문 (취소 제외) : 매출 리포트, 대시보드
    @Query("select o from CustomerOrder o where o.orderedAt >= :from and o.orderedAt < :to and o.status <> com.teagather.order.constant.OrderStatus.CANCELED")
    List<CustomerOrder> findValidBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // 주문번호 : 그날 몇 번째 주문인지
    long countByOrderedAtGreaterThanEqualAndOrderedAtLessThan(LocalDateTime from, LocalDateTime to);
}
