package com.teagather.member.constant;

import lombok.Getter;

// 로그인한 사람에게 부여할 권한
@Getter
public enum MemberRole {
    ADMIN("ROLE_ADMIN", "관리자"),       // 관리자 모드(/admin) : 상품·주문·고객·분석·매출
    CUSTOMER("ROLE_CUSTOMER", "고객");   // 고객 : 장바구니·주문·내 정보

    private final String value;
    private final String label;

    MemberRole(String value, String label) {
        this.value = value;
        this.label = label;
    }
}
