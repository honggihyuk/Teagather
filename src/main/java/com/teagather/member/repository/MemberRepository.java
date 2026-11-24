package com.teagather.member.repository;

import com.teagather.member.constant.MemberRole;
import com.teagather.member.entity.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
