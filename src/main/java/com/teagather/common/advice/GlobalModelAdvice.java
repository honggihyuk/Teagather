package com.teagather.common.advice;

import com.teagather.member.entity.Member;
import com.teagather.member.repository.MemberRepository;
import com.teagather.order.cart.Cart;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;

// 모든 화면에서 쓰는 값 : 로그인 회원, 현재 주소(메뉴 강조), 장바구니 수량
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final MemberRepository memberRepository;
    private final Cart cart;

    @ModelAttribute("loginMember")
    public Member loginMember(Principal principal) {
        if (principal == null) return null;
        return memberRepository.findByUsername(principal.getName()).orElse(null);
    }

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }

    // 장바구니 배지 : 세션이 있을 때만 (없으면 세션을 새로 만들지 않는다)
    @ModelAttribute("cartCount")
    public int cartCount(HttpServletRequest request) {
        if (request.getSession(false) == null) return 0;
        return cart.getCount();
    }
}
