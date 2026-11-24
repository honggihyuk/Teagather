package com.teagather.order.cart;

import lombok.Getter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 장바구니 : 로그인 세션에 보관 (로그아웃하면 비워진다)
@Getter
@Component
@SessionScope
public class Cart implements Serializable {

    private final List<CartLine> lines = new ArrayList<>();

    private String orderToken;          // 주문서 중복 제출 방지 (한 번 쓰면 새로 만든다)

    public void add(CartLine line) {
        for (CartLine l : lines) {
            if (l.sameChoice(line.getProductId(), line.getOptionIds())) {
                l.setQuantity(Math.min(20, l.getQuantity() + line.getQuantity()));
                return;
            }
        }
        lines.add(line);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public int getCount() {
        return lines.stream().mapToInt(CartLine::getQuantity).sum();
    }

    public int getTotalAmount() {
        return lines.stream().mapToInt(l -> l.getUnitPrice() * l.getQuantity()).sum();
    }

    public int getDiscountAmount() {
        return lines.stream().mapToInt(l -> l.getUnitDiscount() * l.getQuantity()).sum();
    }

    public int getPayAmount() {
        return getTotalAmount() - getDiscountAmount();
    }

    public void clear() {
        lines.clear();
    }

    public String issueToken() {
        orderToken = UUID.randomUUID().toString();
        return orderToken;
    }

    // 토큰이 맞으면 true 를 돌려주고 바로 폐기한다
    public boolean useToken(String token) {
        if (token == null || !token.equals(orderToken)) return false;
        orderToken = null;
        return true;
    }
}
