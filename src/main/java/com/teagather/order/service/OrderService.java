package com.teagather.order.service;

import com.teagather.common.exception.BusinessException;
import com.teagather.member.entity.Member;
import com.teagather.order.cart.Cart;
import com.teagather.order.cart.CartLine;
import com.teagather.order.constant.OrderStatus;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.entity.OrderItem;
import com.teagather.order.repository.OrderRepository;
import com.teagather.product.entity.Discount;
import com.teagather.product.entity.OptionGroup;
import com.teagather.product.entity.OptionItem;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.ProductRepository;
import com.teagather.product.service.DiscountService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

// 장바구니 담기 · 주문 · 주문 상태
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final DiscountService discountService;

    // ===== 장바구니 =====

    // 옵션 규칙(필수·하나만)을 확인하고 장바구니 줄을 만든다
    @Transactional(readOnly = true)
    public CartLine makeLine(Long productId, List<Long> optionIds, int quantity) {
        Product p = productRepository.findById(productId).orElseThrow(() -> new EntityNotFoundException("메뉴를 찾을 수 없어요."));
        if (!p.isOrderable()) throw new BusinessException("지금은 주문할 수 없는 메뉴예요.");
        if (quantity < 1 || quantity > 20) throw new BusinessException("수량은 1~20개예요.");
        Set<Long> chosen = new LinkedHashSet<>(optionIds == null ? List.of() : optionIds);

        List<OptionItem> picked = new ArrayList<>();
        for (OptionGroup g : p.getOptionGroups()) {
            List<OptionItem> inGroup = g.getItems().stream().filter(it -> chosen.contains(it.getId())).toList();
            if (g.isRequired() && inGroup.isEmpty()) throw new BusinessException(g.getName() + "을(를) 골라 주세요.");
            if (!g.isMultiple() && inGroup.size() > 1) throw new BusinessException(g.getName() + "은(는) 하나만 고를 수 있어요.");
            picked.addAll(inGroup);
        }
        if (picked.size() != chosen.size()) throw new BusinessException("이 메뉴에 없는 옵션이 있어요.");

        int extra = picked.stream().mapToInt(OptionItem::getExtraPrice).sum();
        String text = picked.stream().map(it -> it.getName() + (it.getExtraPrice() > 0 ? String.format(" +%,d", it.getExtraPrice()) : ""))
                .reduce((a, b) -> a + " · " + b).orElse("");
        Discount d = discountService.activeMap(LocalDate.now()).get(p.getId());
        int unitDiscount = d == null ? 0 : d.amountFor(p.getPrice());
        List<Long> ids = picked.stream().map(OptionItem::getId).sorted().toList();
        return new CartLine(p.getId(), p.getName(), ids, text, p.getPrice() + extra, unitDiscount, quantity,
                p.getCategory().getToneOrDefault(), p.getCategory().getIconOrDefault());
    }

    // ===== 주문 =====

    @Transactional
    public CustomerOrder place(Member member, Cart cart, PickupType pickupType, String request) {
        if (cart.isEmpty()) throw new BusinessException("장바구니가 비어 있어요.");
        LocalDateTime now = LocalDateTime.now();
        CustomerOrder o = CustomerOrder.builder()
                .member(member)
                .status(OrderStatus.PLACED)
                .pickupType(pickupType == null ? PickupType.TAKEOUT : pickupType)
                .request(request == null || request.isBlank() ? null : request.trim())
                .orderedAt(now)
                .build();
        for (CartLine l : cart.getLines()) {
            CartLine fresh = makeLine(l.getProductId(), l.getOptionIds(), l.getQuantity());   // 가격·할인을 지금 기준으로
            Product p = productRepository.getReferenceById(fresh.getProductId());
            o.addItem(OrderItem.builder()
                    .product(p)
                    .productName(fresh.getProductName())
                    .optionText(fresh.getOptionText())
                    .unitPrice(fresh.getUnitPrice())
                    .quantity(fresh.getQuantity())
                    .discountAmount(fresh.getUnitDiscount() * fresh.getQuantity())
                    .lineAmount(fresh.getLineAmount())
                    .build());
        }
        fillAmounts(o);
        o.setOrderNo(nextOrderNo(now));
        orderRepository.save(o);
        applyToMember(member, o, +1);
        cart.clear();
        return o;
    }

    public static void fillAmounts(CustomerOrder o) {
        int total = o.getItems().stream().mapToInt(i -> i.getUnitPrice() * i.getQuantity()).sum();
        int disc = o.getItems().stream().mapToInt(OrderItem::getDiscountAmount).sum();
        o.setTotalAmount(total);
        o.setDiscountAmount(disc);
        o.setPayAmount(total - disc);
        o.setEarnedPoint((total - disc) / 100);
    }

    // 고객 집계 : sign = +1 주문, -1 취소
    public static void applyToMember(Member m, CustomerOrder o, int sign) {
        if (m == null) return;
        m.setOrderCount(Math.max(0, m.getOrderCount() + sign));
        m.setTotalSpent(Math.max(0, m.getTotalSpent() + sign * (long) o.getPayAmount()));
        m.setPoint(Math.max(0, m.getPoint() + sign * o.getEarnedPoint()));
        if (sign > 0 && (m.getLastOrderAt() == null || o.getOrderedAt().isAfter(m.getLastOrderAt()))) {
            m.setLastOrderAt(o.getOrderedAt());
        }
    }

    private String nextOrderNo(LocalDateTime now) {
        LocalDateTime start = now.toLocalDate().atStartOfDay();
        long n = orderRepository.countByOrderedAtGreaterThanEqualAndOrderedAtLessThan(start, start.plusDays(1));
        String no;
        do {
            n++;
            no = now.format(NO_DAY) + "-" + String.format("%04d", n);
        } while (orderRepository.existsByOrderNo(no));
        return no;
    }

    @Transactional(readOnly = true)
    public List<CustomerOrder> myOrders(Member member) {
        List<CustomerOrder> list = orderRepository.findByMemberIdOrderByOrderedAtDesc(member.getId(), PageRequest.of(0, 50));
        list.forEach(o -> o.getItems().size());
        return list;
    }

    @Transactional(readOnly = true)
    public CustomerOrder get(Long id) {
        CustomerOrder o = orderRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("주문을 찾을 수 없어요."));
        o.getItems().size();
        return o;
    }

    // 고객 : 내 주문만, 접수 상태일 때만 취소
    @Transactional
    public CustomerOrder cancelByCustomer(Long id, Member member) {
        CustomerOrder o = get(id);
        if (o.getMember() == null || !o.getMember().getId().equals(member.getId())) throw new EntityNotFoundException("주문을 찾을 수 없어요.");
        if (o.getStatus() != OrderStatus.PLACED) throw new BusinessException("이미 준비를 시작한 주문은 매장에 문의해 주세요.");
        o.setStatus(OrderStatus.CANCELED);
        applyToMember(o.getMember(), o, -1);
        return o;
    }

    // 관리자 : 다음 단계로
    @Transactional
    public CustomerOrder advance(Long id) {
        CustomerOrder o = get(id);
        OrderStatus next = o.getStatus().next();
        if (next == null) throw new BusinessException("더 진행할 단계가 없어요.");
        o.setStatus(next);
        return o;
    }

    @Transactional
    public CustomerOrder cancelByAdmin(Long id) {
        CustomerOrder o = get(id);
        if (!o.getStatus().isOpen()) throw new BusinessException("완료되었거나 이미 취소된 주문이에요.");
        o.setStatus(OrderStatus.CANCELED);
        applyToMember(o.getMember(), o, -1);
        return o;
    }

    @Transactional(readOnly = true)
    public List<CustomerOrder> adminList(OrderStatus status) {
        List<CustomerOrder> list = orderRepository.findForAdmin(status, PageRequest.of(0, 100));
        list.forEach(o -> o.getItems().size());
        return list;
    }

    @Transactional(readOnly = true)
    public Map<OrderStatus, Long> counts() {
        Map<OrderStatus, Long> map = new EnumMap<>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) map.put(s, orderRepository.countByStatus(s));
        return map;
    }
}
