package com.teagather.admin.controller;

import com.teagather.analysis.service.AnalysisService;
import com.teagather.member.entity.Member;
import com.teagather.member.repository.MemberRepository;
import com.teagather.member.service.MemberService;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.repository.OrderItemRepository;
import com.teagather.order.service.OrderService;
import com.teagather.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.*;

// 관리자 : 고객 정보 · 고객 카드
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/customers")
public class AdminCustomerController {

    private final MemberRepository memberRepository;
    private final MemberService memberService;
    private final OrderService orderService;
    private final OrderItemRepository orderItemRepository;
    private final AnalysisService analysisService;
    private final ProductService productService;

    @GetMapping
    public String list(@RequestParam(value = "q", required = false) String q,
                       @RequestParam(value = "segment", required = false) String segment,
                       @RequestParam(value = "sort", defaultValue = "spent") String sort,
                       @RequestParam(value = "page", defaultValue = "0") int page, Model model) {
        String query = (q == null || q.isBlank()) ? null : q.trim();
        String seg = (segment == null || segment.isBlank()) ? null : segment;
        Sort s = switch (sort) {
            case "recent" -> Sort.by(Sort.Order.desc("lastOrderAt").nullsLast());
            case "risk" -> Sort.by(Sort.Order.desc("churnRisk").nullsLast());
            case "joined" -> Sort.by(Sort.Order.desc("created"));
            default -> Sort.by(Sort.Order.desc("totalSpent"));
        };
        Page<Member> customers = memberRepository.searchCustomers(query, seg, PageRequest.of(Math.max(0, page), 20, s));
        model.addAttribute("customers", customers);
        model.addAttribute("q", query);
        model.addAttribute("segment", seg);
        model.addAttribute("sort", sort);
        model.addAttribute("segments", analysisService.latestSuccess().map(r -> analysisService.segments(r.getId())).orElse(List.of()));
        return "admin/customers";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable("id") Long id, Model model) {
        Member c = memberService.get(id);
        List<CustomerOrder> orders = orderService.myOrders(c);
        model.addAttribute("c", c);
        model.addAttribute("orders", orders.stream().limit(20).toList());
        model.addAttribute("favorites", orderItemRepository.findFavorites(id).stream().limit(3).toList());
        // 선호 시간대
        Map<String, Integer> slots = new LinkedHashMap<>();
        slots.put("아침 (~11시)", 0);
        slots.put("낮 (11~17시)", 0);
        slots.put("저녁 (17시~)", 0);
        for (CustomerOrder o : orders) {
            int h = o.getOrderedAt().getHour();
            slots.merge(h < 11 ? "아침 (~11시)" : h < 17 ? "낮 (11~17시)" : "저녁 (17시~)", 1, Integer::sum);
        }
        model.addAttribute("favoriteSlot", orders.isEmpty() ? "-" : Collections.max(slots.entrySet(), Map.Entry.comparingByValue()).getKey());
        LinkedHashMap<Long, String> recs = analysisService.recommendationsFor(id);
        model.addAttribute("recommended", productService.cardsByIds(new ArrayList<>(recs.keySet()), recs));
        return "admin/customer-detail";
    }

    @PostMapping("/{id}/active")
    public String toggle(@PathVariable("id") Long id, RedirectAttributes ra) {
        Member m = memberService.toggleActive(id);
        ra.addFlashAttribute("adminMessage", m.getDisplayName() + (m.isActive() ? " 고객을 다시 사용하도록 했어요." : " 고객을 정지했어요."));
        return "redirect:/admin/customers/" + id;
    }
}
