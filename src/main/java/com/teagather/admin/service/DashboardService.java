package com.teagather.admin.service;

import com.teagather.admin.dto.AdminDashboard;
import com.teagather.admin.dto.Bar;
import com.teagather.analysis.entity.AnalysisRun;
import com.teagather.analysis.entity.MenuInsight;
import com.teagather.analysis.service.AnalysisService;
import com.teagather.member.constant.MemberRole;
import com.teagather.member.repository.MemberRepository;
import com.teagather.order.constant.OrderStatus;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.repository.OrderItemRepository;
import com.teagather.order.repository.OrderRepository;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final MemberRepository memberRepository;
    private final AnalysisService analysisService;

    @Transactional(readOnly = true)
    public AdminDashboard build() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        List<CustomerOrder> week = orderRepository.findValidBetween(today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay());

        long todayRev = 0, yesterdaySameTime = 0, yesterdayRev = 0;
        int todayOrders = 0;
        Map<LocalDate, Long> perDay = new TreeMap<>();
        for (int i = 6; i >= 0; i--) perDay.put(today.minusDays(i), 0L);
        for (CustomerOrder o : week) {
            LocalDate d = o.getOrderedAt().toLocalDate();
            perDay.merge(d, (long) o.getPayAmount(), Long::sum);
            if (d.equals(today)) { todayRev += o.getPayAmount(); todayOrders++; }
            if (d.equals(today.minusDays(1))) {
                yesterdayRev += o.getPayAmount();
                if (!o.getOrderedAt().toLocalTime().isAfter(now.toLocalTime())) yesterdaySameTime += o.getPayAmount();
            }
        }
        long max = Math.max(1, perDay.values().stream().mapToLong(Long::longValue).max().orElse(1));
        List<Bar> weekBars = perDay.entrySet().stream().map(e -> new Bar(
                e.getKey().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN), e.getValue(),
                (int) (e.getValue() * 100 / max), e.getKey().equals(today), e.getKey().getMonthValue() + "." + e.getKey().getDayOfMonth())).toList();

        // 최근 7일 많이 팔린 메뉴
        Map<Long, Integer> qty = new HashMap<>();
        for (Object[] r : orderItemRepository.findSalesRows(today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay())) {
            qty.merge((Long) r[3], ((Number) r[4]).intValue(), Integer::sum);
        }
        Map<Long, String> names = productRepository.findAllById(qty.keySet()).stream().collect(Collectors.toMap(Product::getId, Product::getName));
        int topMax = qty.values().stream().mapToInt(Integer::intValue).max().orElse(1);
        List<Bar> top = qty.entrySet().stream().sorted(Map.Entry.<Long, Integer>comparingByValue().reversed()).limit(5)
                .map(e -> new Bar(names.getOrDefault(e.getKey(), "-"), e.getValue(), e.getValue() * 100 / Math.max(1, topMax), false, null)).toList();

        List<CustomerOrder> open = orderRepository.findForAdmin(null, PageRequest.of(0, 40)).stream()
                .filter(o -> o.getStatus().isOpen()).limit(5).toList();
        open.forEach(o -> o.getItems().size());

        AnalysisRun run = analysisService.latestSuccess().orElse(null);
        List<MenuInsight> picks = new ArrayList<>();
        if (run != null) analysisService.picks(run.getId()).values().forEach(l -> { if (!l.isEmpty()) picks.add(l.get(0)); });

        return AdminDashboard.builder()
                .todayRevenue(todayRev).yesterdayRevenue(yesterdayRev)
                .changePct(yesterdaySameTime == 0 ? null : (int) Math.round((todayRev - yesterdaySameTime) * 100.0 / yesterdaySameTime))
                .todayOrders(todayOrders)
                .openOrders(orderRepository.countByStatusIn(List.of(OrderStatus.PLACED, OrderStatus.PREPARING, OrderStatus.READY)))
                .newCustomers(memberRepository.countByRoleAndCreatedGreaterThanEqual(MemberRole.CUSTOMER, today.withDayOfMonth(1).atStartOfDay()))
                .customers(memberRepository.countByRole(MemberRole.CUSTOMER))
                .week(weekBars).topMenus(top).recentOpen(open).run(run).topPicks(picks)
                .build();
    }
}
