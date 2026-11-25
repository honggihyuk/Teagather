package com.teagather.order.service;

import com.teagather.common.exception.BusinessException;
import com.teagather.member.entity.Member;
import com.teagather.member.repository.MemberRepository;
import com.teagather.order.cart.Cart;
import com.teagather.order.cart.CartLine;
import com.teagather.order.constant.OrderStatus;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.entity.OrderItem;
import com.teagather.order.repository.OrderDaySeqRepository;
import com.teagather.order.repository.OrderRepository;
import com.teagather.product.entity.Discount;
import com.teagather.product.entity.OptionGroup;
import com.teagather.product.entity.OptionItem;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.ProductRepository;
import com.teagather.product.service.DiscountService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.Hibernate;
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
    private final OrderDaySeqRepository daySeqRepository;
    private final MemberRepository memberRepository;
    private final EntityManager em;

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

    // 주문 한 번 = 트랜잭션 한 번.
    //  - 장바구니는 비우지 않는다 : 커밋이 끝난 뒤 호출한 쪽(컨트롤러)이 비운다. 실패하면 장바구니가 그대로 남는다
    //  - 주문번호 충돌(DataIntegrityViolationException)은 여기서 잡지 않는다 : 트랜잭션 바깥에서 새로 다시 시도해야 한다
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
        if (member != null) {
            memberRepository.addOrder(member.getId(), o.getPayAmount(), o.getEarnedPoint(), o.getOrderedAt());
            syncFromDb(member);
        }
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

    // 고객 집계 (예시 데이터 만들 때만 사용) : sign = +1 주문, -1 취소
    //  - 실제 주문·취소는 MemberRepository.addOrder/removeOrder 로 DB 에서 바로 더하고 뺀다 (동시 요청에도 값이 사라지지 않게)
    public static void applyToMember(Member m, CustomerOrder o, int sign) {
        if (m == null) return;
        m.setOrderCount(Math.max(0, m.getOrderCount() + sign));
        m.setTotalSpent(Math.max(0, m.getTotalSpent() + sign * (long) o.getPayAmount()));
        m.setPoint(Math.max(0, m.getPoint() + sign * o.getEarnedPoint()));
        if (sign > 0 && (m.getLastOrderAt() == null || o.getOrderedAt().isAfter(m.getLastOrderAt()))) {
            m.setLastOrderAt(o.getOrderedAt());
        }
    }

    // 주문번호 : yyMMdd-0001. 일자별 카운터 줄을 +1 하고 그 값을 쓴다
    //  - +1 한 줄의 행 잠금은 이 주문 트랜잭션이 끝날 때까지 유지 → 같은 날 주문은 번호 발급 구간만 줄을 선다
    //  - 그날 첫 주문이면 기존 주문의 마지막 번호부터 이어서 (예시 데이터·이전 방식으로 만든 주문과 겹치지 않게)
    private String nextOrderNo(LocalDateTime now) {
        LocalDate day = now.toLocalDate();
        String prefix = now.format(NO_DAY);
        int seed = daySeqRepository.existsById(day) ? 0 : lastSeqOf(prefix);
        daySeqRepository.increment(day, seed);
        int n = daySeqRepository.currentNo(day);
        return prefix + "-" + String.format("%04d", n);
    }

    private int lastSeqOf(String prefix) {
        String max = orderRepository.findMaxOrderNo(prefix);
        if (max == null) return 0;
        try {
            return Integer.parseInt(max.substring(prefix.length() + 1));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // DB 에서 바로 고친 회원 집계를 메모리의 Member 에도 반영 (나중에 이 객체가 다시 저장되며 옛 값으로 덮어쓰지 않게)
    private void syncFromDb(Member m) {
        if (m != null && Hibernate.isInitialized(m) && em.contains(m)) em.refresh(m);
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
        removeFromMember(o);
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
        removeFromMember(o);
        return o;
    }

    private void removeFromMember(CustomerOrder o) {
        Member m = o.getMember();
        if (m == null) return;
        memberRepository.removeOrder(m.getId(), o.getPayAmount(), o.getEarnedPoint());
        syncFromDb(m);
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
