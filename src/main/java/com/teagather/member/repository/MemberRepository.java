package com.teagather.member.repository;

import com.teagather.member.constant.MemberRole;
import com.teagather.member.entity.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<Member> findByEmail(String email);

    List<Member> findByRole(MemberRole role);

    long countByRole(MemberRole role);

    long countByRoleAndCreatedGreaterThanEqual(MemberRole role, LocalDateTime from);

    // 관리자 : 고객 목록 (이름·아이디·연락처 검색 + 고객군)
    @Query("select m from Member m where m.role = com.teagather.member.constant.MemberRole.CUSTOMER "
            + "and (:q is null or lower(m.name) like lower(concat('%', :q, '%')) or lower(m.username) like lower(concat('%', :q, '%')) or m.phone like concat('%', :q, '%')) "
            + "and (:segment is null or m.segmentCode = :segment)")
    Page<Member> searchCustomers(@Param("q") String q, @Param("segment") String segment, Pageable pageable);

    // 고객 분석 : 이탈 위험이 높은 순 (주문이 한 번 이상 있는 고객)
    @Query("select m from Member m where m.role = com.teagather.member.constant.MemberRole.CUSTOMER "
            + "and m.churnRisk is not null and m.orderCount > 0 order by m.churnRisk desc, m.totalSpent desc")
    List<Member> findChurnTop(Pageable pageable);

    // ---------- 구매 집계 : DB 에서 한 문장으로 더하고 빼기 (읽고-고치고-쓰기를 하지 않아 동시 주문·취소에도 값이 사라지지 않는다) ----------

    // 주문 : 주문 수 +1, 누적 결제 +amount, 포인트 +point, 마지막 주문 시각은 앞으로만
    @Modifying(flushAutomatically = true)
    @Query("update Member m set m.orderCount = m.orderCount + 1, m.totalSpent = m.totalSpent + :amount, "
            + "m.point = m.point + :point, "
            + "m.lastOrderAt = case when m.lastOrderAt is null or m.lastOrderAt < :at then :at else m.lastOrderAt end "
            + "where m.id = :id")
    int addOrder(@Param("id") Long id, @Param("amount") long amount, @Param("point") int point, @Param("at") LocalDateTime at);

    // 취소 : 0 아래로 내려가지 않는다
    @Modifying(flushAutomatically = true)
    @Query("update Member m set m.orderCount = greatest(0, m.orderCount - 1), m.totalSpent = greatest(0, m.totalSpent - :amount), "
            + "m.point = greatest(0, m.point - :point) where m.id = :id")
    int removeOrder(@Param("id") Long id, @Param("amount") long amount, @Param("point") int point);
}
