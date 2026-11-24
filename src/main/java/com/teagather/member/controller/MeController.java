package com.teagather.member.controller;

import com.teagather.analysis.service.AnalysisService;
import com.teagather.member.entity.Member;
import com.teagather.member.service.MemberService;
import com.teagather.order.service.OrderService;
import com.teagather.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;

// 내 정보 : 등급 · 포인트 · 최근 주문 · 추천 메뉴
@Controller
@RequiredArgsConstructor
public class MeController {

    private final MemberService memberService;
    private final OrderService orderService;
    private final AnalysisService analysisService;
    private final ProductService productService;

    @GetMapping("/me")
    public String me(Principal principal, Model model) {
        Member me = memberService.getByUsername(principal.getName());
        model.addAttribute("me", me);
        model.addAttribute("recentOrders", orderService.myOrders(me).stream().limit(3).toList());
        LinkedHashMap<Long, String> recs = analysisService.recommendationsFor(me.getId());
        model.addAttribute("recommended", productService.cardsByIds(new ArrayList<>(recs.keySet()), recs));
        return "member/me";
    }
}
