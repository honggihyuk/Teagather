package com.teagather.shop.controller;

import com.teagather.common.exception.BusinessException;
import com.teagather.member.entity.Member;
import com.teagather.member.service.MemberService;
import com.teagather.order.cart.Cart;
import com.teagather.order.constant.OrderStatus;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.service.OrderService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

// 주문서 · 내 주문
@Controller
@RequiredArgsConstructor
public class OrderController {

    private final Cart cart;
    private final OrderService orderService;
    private final MemberService memberService;

    @GetMapping("/order")
    public String checkout(Model model) {
        if (cart.isEmpty()) return "redirect:/cart";
        model.addAttribute("cart", cart);
        model.addAttribute("orderToken", cart.issueToken());
        model.addAttribute("pickupTypes", PickupType.values());
        return "shop/checkout";
    }

    @PostMapping("/order")
    public String place(@RequestParam(value = "pickupType", defaultValue = "TAKEOUT") PickupType pickupType,
                        @RequestParam(value = "request", required = false) String request,
                        @RequestParam(value = "orderToken", required = false) String token,
                        Principal principal, RedirectAttributes ra) {
        if (!cart.useToken(token)) {
            ra.addFlashAttribute("errorMessage", "이미 처리한 주문서예요. 주문 내역을 확인해 주세요.");
            return "redirect:/orders";
        }
        if (request != null && request.length() > 200) throw new BusinessException("요청사항은 200자까지예요.");
        Member me = memberService.getByUsername(principal.getName());
        CustomerOrder o = orderService.place(me, cart, pickupType, request);
        ra.addFlashAttribute("shopMessage", "주문을 받았어요. 주문번호 " + o.getOrderNo());
        return "redirect:/orders/" + o.getId() + "?done";
    }

    @GetMapping("/orders")
    public String myOrders(Principal principal, Model model) {
        model.addAttribute("orders", orderService.myOrders(memberService.getByUsername(principal.getName())));
        return "shop/orders";
    }

    @GetMapping("/orders/{id}")
    public String detail(@PathVariable("id") Long id, @RequestParam(value = "done", required = false) String done,
                         Principal principal, Model model) {
        Member me = memberService.getByUsername(principal.getName());
        CustomerOrder o = orderService.get(id);
        if (o.getMember() == null || !o.getMember().getId().equals(me.getId())) throw new EntityNotFoundException("주문을 찾을 수 없어요.");
        model.addAttribute("order", o);
        model.addAttribute("done", done != null);
        model.addAttribute("steps", new OrderStatus[]{OrderStatus.PLACED, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.COMPLETED});
        return "shop/order-detail";
    }

    @PostMapping("/orders/{id}/cancel")
    public String cancel(@PathVariable("id") Long id, Principal principal, RedirectAttributes ra) {
        orderService.cancelByCustomer(id, memberService.getByUsername(principal.getName()));
        ra.addFlashAttribute("shopMessage", "주문을 취소했어요.");
        return "redirect:/orders/" + id;
    }
}
