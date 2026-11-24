package com.teagather.member.entity;

import com.teagather.member.constant.CustomerGrade;
import com.teagather.member.constant.MemberRole;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

// 회원 (고객 + 관리자)
@Entity
@Getter
@Setter
@ToString(exclude = "password")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 30)
    private String username;                // 로그인 아이디 (중복 불가)

    private String password;                // BCrypt 로 암호화해서 저장

    @Column(length = 20)
    private String name;                    // 이름

    @Column(length = 30)
    private String phone;                   // 연락처 (선택)

    @Column(unique = true)
    private String email;                   // 이메일 (선택, 안 쓰면 null)

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MemberRole role;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;          // false 면 로그인 불가 (정지)

    private boolean marketingAgree;         // 혜택 알림 동의

    private LocalDateTime created;          // 가입일

    // ---------- 구매 집계 (주문할 때·취소할 때 갱신) ----------
    private int orderCount;                 // 주문 수 (취소 제외)
    private long totalSpent;                // 누적 결제 금액
    private int point;                      // 적립 포인트 (결제 금액의 1%)
    private LocalDateTime lastOrderAt;      // 마지막 주문

    // ---------- 분석 결과 (PyTorch) ----------
    @Column(length = 20)
    private String segmentCode;             // VIP · LOYAL · NEW · OCCASIONAL · AT_RISK
    @Column(length = 20)
    private String segmentLabel;            // VIP · 단골 · 신규 · 가끔 방문 · 이탈 위험
    private Double churnRisk;               // 21일 안에 다시 오지 않을 확률 (0~1)

    public String getDisplayName() {
        return (name == null || name.isBlank()) ? username : name;
    }

    // 동그라미에 넣을 글자 (이름 뒤 2글자)
    public String getInitials() {
        String n = getDisplayName();
        return n.length() <= 2 ? n : n.substring(n.length() - 2);
    }

    public CustomerGrade getGrade() {
        return CustomerGrade.of(totalSpent);
    }

    public boolean isAdmin() {
        return role == MemberRole.ADMIN;
    }

    // 이탈 위험 % (없으면 null)
    public Integer getChurnPercent() {
        return churnRisk == null ? null : (int) Math.round(churnRisk * 100);
    }

    public long getAvgBasket() {
        return orderCount == 0 ? 0 : totalSpent / orderCount;
    }
}
