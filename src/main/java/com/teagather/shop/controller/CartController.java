package com.teagather.shop.controller;

import com.teagather.order.cart.Cart;
import com.teagather.order.cart.CartLine;
import com.teagather.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import org.springframework.util.MultiValueMap;

// 장바구니 (로그인 필요)
@Controller
@RequiredArgsConstructor
@RequestMapping("/cart")
public class CartController {

    private final Cart cart;
    private final OrderService orderService;

    @GetMapping
    public String cart(Model model) {
        model.addAttribute("cart", cart);
        return "shop/cart";
    }

    @PostMapping("/add")
    public String add(@RequestParam("productId") Long productId,
                      @RequestParam MultiValueMap<String, String> params,
                      @RequestParam(value = "quantity", defaultValue = "1") int quantity,
                      @RequestParam(value = "go", required = false) String go,
                      RedirectAttributes ra) {
        // 옵션 입력 이름 : opt_{그룹 id} (라디오는 그룹마다 이름이 달라야 하나씩 고를 수 있다)
        List<Long> optionIds = new ArrayList<>();
        params.forEach((k, vs) -> { if (k.startsWith("opt_")) vs.forEach(v -> optionIds.add(Long.valueOf(v))); });
        CartLine line = orderService.makeLine(productId, optionIds, quantity);
        cart.add(line);
        if ("order".equals(go)) return "redirect:/order";
        ra.addFlashAttribute("shopMessage", line.getProductName() + " 을(를) 장바구니에 담았어요.");
        return "redirect:/menu/" + productId;
    }

    @PostMapping("/{index}/qty")
    public String qty(@PathVariable("index") int index, @RequestParam("quantity") int quantity) {
        cart.updateQuantity(index, quantity);
        return "redirect:/cart";
    }

    @PostMapping("/{index}/delete")
    public String delete(@PathVariable("index") int index) {
        cart.removeAt(index);
        return "redirect:/cart";
    }
}
