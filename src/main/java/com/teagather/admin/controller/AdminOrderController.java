package com.teagather.admin.controller;

import com.teagather.order.constant.OrderStatus;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// 관리자 : 주문 관리 (상태 진행 · 취소)
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    @GetMapping
    public String list(@RequestParam(value = "status", required = false) OrderStatus status, Model model) {
        model.addAttribute("status", status);
        model.addAttribute("statuses", OrderStatus.values());
        model.addAttribute("counts", orderService.counts());
        model.addAttribute("orders", orderService.adminList(status));
        return "admin/orders";
    }

    @PostMapping("/{id}/advance")
    public String advance(@PathVariable("id") Long id, @RequestParam(value = "status", required = false) String status,
                          RedirectAttributes ra) {
        CustomerOrder o = orderService.advance(id);
        ra.addFlashAttribute("adminMessage", o.getOrderNo() + " → " + o.getStatus().getLabel());
        return back(status);
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable("id") Long id, @RequestParam(value = "status", required = false) String status,
                         RedirectAttributes ra) {
        CustomerOrder o = orderService.cancelByAdmin(id);
        ra.addFlashAttribute("adminMessage", o.getOrderNo() + " 주문을 취소했어요.");
        return back(status);
    }

    private static String back(String status) {
        return "redirect:/admin/orders" + (status == null || status.isBlank() ? "" : "?status=" + OrderStatus.valueOf(status).name());
    }
}
