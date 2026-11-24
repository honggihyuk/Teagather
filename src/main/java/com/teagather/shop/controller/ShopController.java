package com.teagather.shop.controller;

import com.teagather.analysis.service.AnalysisService;
import com.teagather.member.entity.Member;
import com.teagather.member.repository.MemberRepository;
import java.security.Principal;
import com.teagather.product.dto.ProductCard;
import com.teagather.product.service.CatalogAdminService;
import com.teagather.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

// 고객 사이트 : 첫 화면 · 메뉴 목록 · 메뉴 상세 (로그인 없이 볼 수 있다)
@Controller
@RequiredArgsConstructor
public class ShopController {

    private final ProductService productService;
    private final CatalogAdminService catalogAdminService;
    private final AnalysisService analysisService;
    private final MemberRepository memberRepository;

    // 첫 화면 : 오늘의 메뉴 · 오늘의 할인 · 신메뉴 · 추천 메뉴
    @GetMapping("/")
    public String home(Principal principal, Model model) {
        Member loginMember = principal == null ? null : memberRepository.findByUsername(principal.getName()).orElse(null);
        model.addAttribute("todayMenus", productService.todayCards());
        model.addAttribute("discountMenus", productService.discountCards());
        model.addAttribute("newMenus", productService.newCards());
        model.addAttribute("categories", catalogAdminService.categories());

        List<ProductCard> recommended = new ArrayList<>();
        String title = "많이 찾는 메뉴";
        String sub = "최근 30일 동안 가장 많이 주문된 메뉴예요";
        if (loginMember != null && !loginMember.isAdmin()) {
            LinkedHashMap<Long, String> recs = analysisService.recommendationsFor(loginMember.getId());
            if (!recs.isEmpty()) {
                recommended = productService.cardsByIds(new ArrayList<>(recs.keySet()), recs);
                title = loginMember.getDisplayName() + "님께 추천해요";
                sub = "주문 기록을 학습한 추천 모델이 고른 메뉴예요";
            }
        }
        if (recommended.isEmpty()) {
            recommended = productService.cardsByIds(analysisService.popularProductIds(4), null);
        }
        model.addAttribute("recommended", recommended.stream().limit(4).toList());
        model.addAttribute("recommendTitle", title);
        model.addAttribute("recommendSub", sub);
        return "shop/home";
    }

    @GetMapping("/menu")
    public String menu(@RequestParam(value = "category", required = false) Long category, Model model) {
        model.addAttribute("categories", catalogAdminService.categories());
        model.addAttribute("category", category);
        model.addAttribute("products", productService.visibleCards(category));
        return "shop/menu-list";
    }

    @GetMapping("/menu/{id}")
    public String detail(@PathVariable("id") Long id, Model model) {
        ProductCard c = productService.card(id);
        model.addAttribute("c", c);
        model.addAttribute("groups", c.getProduct().getOptionGroups());
        return "shop/menu-detail";
    }
}
