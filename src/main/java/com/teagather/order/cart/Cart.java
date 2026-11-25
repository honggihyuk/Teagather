package com.teagather.order.cart;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 장바구니 : 로그인 세션에 보관 (로그아웃하면 비워진다)
//  같은 세션에서 요청이 동시에 들어올 수 있으므로 (더블클릭, 탭 두 개) 모든 읽기·쓰기를 synchronized 로 맞춘다
//  - getLines() 는 복사본을 돌려준다 : 화면(Thymeleaf)·주문 생성이 도는 동안 다른 요청이 목록을 바꿔도 안전
//  - 줄 수정·삭제는 updateQuantity / removeAt 으로만 한다
@Component
@SessionScope
public class Cart implements Serializable {

    private final List<CartLine> lines = new ArrayList<>();

    private String orderToken;          // 주문서 중복 제출 방지 (한 번 쓰면 새로 만든다)

    private String inFlightToken;       // 지금 처리 중인 주문서의 토큰 (더블클릭한 두 번째 요청을 알아보는 용도)

    private String placedToken;         // 마지막으로 "성공한" 주문서의 토큰 (진짜 중복 제출을 알아보는 용도)
    private Long placedOrderId;

    public synchronized void add(CartLine line) {
        for (CartLine l : lines) {
            if (l.sameChoice(line.getProductId(), line.getOptionIds())) {
                l.setQuantity(Math.min(20, l.getQuantity() + line.getQuantity()));
                return;
            }
        }
        lines.add(line);
    }

    public synchronized List<CartLine> getLines() {
        return List.copyOf(lines);
    }

    // 수량 바꾸기 (0 이하면 삭제). 범위 밖 index 는 무시
    public synchronized void updateQuantity(int index, int quantity) {
        if (index < 0 || index >= lines.size()) return;
        if (quantity <= 0) lines.remove(index);
        else lines.get(index).setQuantity(Math.min(20, quantity));
    }

    public synchronized void removeAt(int index) {
        if (index >= 0 && index < lines.size()) lines.remove(index);
    }

    public synchronized boolean isEmpty() {
        return lines.isEmpty();
    }

    public synchronized int getCount() {
        return lines.stream().mapToInt(CartLine::getQuantity).sum();
    }

    public synchronized int getTotalAmount() {
        return lines.stream().mapToInt(l -> l.getUnitPrice() * l.getQuantity()).sum();
    }

    public synchronized int getDiscountAmount() {
        return lines.stream().mapToInt(l -> l.getUnitDiscount() * l.getQuantity()).sum();
    }

    public synchronized int getPayAmount() {
        return getTotalAmount() - getDiscountAmount();
    }

    public synchronized void clear() {
        lines.clear();
    }

    public synchronized String getOrderToken() {
        return orderToken;
    }

    // 토큰 메서드도 synchronized : 같은 세션에서 주문서를 동시에 두 번 보내도 한 번만 통과한다
    public synchronized String issueToken() {
        orderToken = UUID.randomUUID().toString();
        return orderToken;
    }

    // 토큰이 맞으면 true 를 돌려주고 바로 폐기한다 (확인과 폐기가 한 번에 일어난다)
    // 통과한 토큰은 "처리 중" 으로 기억한다 → 끝나기 전에 같은 토큰이 또 오면 더블클릭으로 본다
    public synchronized boolean useToken(String token) {
        if (token == null || !token.equals(orderToken)) return false;
        orderToken = null;
        inFlightToken = token;
        return true;
    }

    // 이 토큰의 주문이 아직 처리 중인가 (첫 요청이 끝나기 전에 들어온 두 번째 요청)
    public synchronized boolean isInFlight(String token) {
        return token != null && token.equals(inFlightToken);
    }

    // 주문이 실패하면 쓴 토큰을 되돌려 같은 주문서로 다시 보낼 수 있게 한다
    // (그 사이 다른 탭에서 새 주문서를 열어 새 토큰이 생겼으면 그대로 둔다)
    public synchronized void restoreToken(String token) {
        if (token != null && token.equals(inFlightToken)) inFlightToken = null;
        if (token != null && orderToken == null) orderToken = token;
    }

    // 주문이 커밋된 뒤 : 장바구니를 비우고 이 토큰으로 만든 주문을 기억한다
    public synchronized void markPlaced(String token, Long orderId) {
        lines.clear();
        if (token != null && token.equals(inFlightToken)) inFlightToken = null;
        placedToken = token;
        placedOrderId = orderId;
    }

    // 이미 성공한 주문서를 다시 보낸 경우 그 주문 id, 아니면 null
    public synchronized Long placedOrderIdFor(String token) {
        return token != null && token.equals(placedToken) ? placedOrderId : null;
    }
}
