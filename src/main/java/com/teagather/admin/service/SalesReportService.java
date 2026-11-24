package com.teagather.admin.service;

import com.teagather.admin.dto.Bar;
import com.teagather.admin.dto.SalesReport;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.repository.OrderItemRepository;
import com.teagather.order.repository.OrderRepository;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

// 매출 리포트 : 기간 안 주문(취소 제외)을 일별·시간대별·카테고리별·메뉴별로 모은다
@Service
@RequiredArgsConstructor
public class SalesReportService {

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("M.d");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public SalesReport build(LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<CustomerOrder> orders = orderRepository.findValidBetween(start, end);
        LocalDate today = LocalDate.now();

        long revenue = orders.stream().mapToLong(CustomerOrder::getPayAmount).sum();
        long discount = orders.stream().mapToLong(CustomerOrder::getDiscountAmount).sum();

        // 일별
        Map<LocalDate, Long> perDay = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) perDay.put(d, 0L);
        Map<Integer, Long> perHour = new TreeMap<>();
        for (int h = 7; h <= 22; h++) perHour.put(h, 0L);
        for (CustomerOrder o : orders) {
            perDay.merge(o.getOrderedAt().toLocalDate(), (long) o.getPayAmount(), Long::sum);
            int h = Math.max(7, Math.min(22, o.getOrderedAt().getHour()));
            perHour.merge(h, (long) o.getPayAmount(), Long::sum);
        }
        List<Bar> daily = bars(perDay, d -> d.format(MD), d -> d.equals(today));
        int peak = perHour.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(-1);
        List<Bar> hourly = bars(perHour, h -> h + "시", h -> h == peak);

        // 메뉴별 · 카테고리별
        Map<Long, Product> products = productRepository.findAllOrdered().stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, long[]> perProduct = new HashMap<>();   // qty, revenue, discountedQty
        for (Object[] r : orderItemRepository.findSalesRows(start, end)) {
            long pid = (Long) r[3];
            int qty = ((Number) r[4]).intValue();
            long[] acc = perProduct.computeIfAbsent(pid, k -> new long[3]);
            acc[0] += qty;
            acc[1] += ((Number) r[5]).longValue();
            if (((Number) r[6]).intValue() > 0) acc[2] += qty;
        }
        long itemRevenue = perProduct.values().stream().mapToLong(a -> a[1]).sum();
        List<SalesReport.ProductRow> rows = new ArrayList<>();
        Map<String, Long> perCat = new LinkedHashMap<>();
        products.values().stream().map(p -> p.getCategory().getName()).distinct().forEach(c -> perCat.put(c, 0L));
        for (Map.Entry<Long, long[]> e : perProduct.entrySet()) {
            Product p = products.get(e.getKey());
            if (p == null) continue;
            long[] a = e.getValue();
            String cat = p.getCategory().getName();
            perCat.merge(cat, a[1], Long::sum);
            rows.add(new SalesReport.ProductRow(p.getId(), p.getName(), cat, (int) a[0], a[1],
                    itemRevenue == 0 ? 0 : Math.round(a[1] * 1000.0 / itemRevenue) / 10.0,
                    a[0] == 0 ? 0 : Math.round(a[2] * 1000.0 / a[0]) / 10.0));
        }
        rows.sort(Comparator.comparingLong(SalesReport.ProductRow::getRevenue).reversed());
        List<Map.Entry<String, Long>> catSorted = new ArrayList<>(perCat.entrySet());
        catSorted.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        long catMax = catSorted.isEmpty() ? 1 : Math.max(1, catSorted.get(0).getValue());
        List<Bar> byCat = catSorted.stream().map(e -> new Bar(e.getKey(), e.getValue(), (int) (e.getValue() * 100 / catMax), false,
                itemRevenue == 0 ? "0%" : Math.round(e.getValue() * 100.0 / itemRevenue) + "%")).toList();

        return SalesReport.builder().from(from).to(to).revenue(revenue).orders(orders.size())
                .avgBasket(orders.isEmpty() ? 0 : revenue / orders.size()).discount(discount)
                .daily(daily).hourly(hourly).byCategory(byCat).byProduct(rows).build();
    }

    // CSV (메뉴별)
    public String csv(SalesReport r) {
        StringBuilder sb = new StringBuilder("﻿메뉴,카테고리,수량,매출,비중(%),할인 판매 비율(%)\n");
        for (SalesReport.ProductRow p : r.getByProduct()) {
            sb.append('"').append(p.getName().replace("\"", "\"\"")).append("\",").append(p.getCategory()).append(',')
                    .append(p.getQty()).append(',').append(p.getRevenue()).append(',')
                    .append(p.getShare()).append(',').append(p.getDiscountShare()).append('\n');
        }
        return sb.toString();
    }

    static <K> List<Bar> bars(Map<K, Long> map, Function<K, String> label, java.util.function.Predicate<K> highlight) {
        long max = Math.max(1, map.values().stream().mapToLong(Long::longValue).max().orElse(1));
        return map.entrySet().stream().map(e -> new Bar(label.apply(e.getKey()), e.getValue(),
                (int) (e.getValue() * 100 / max), highlight.test(e.getKey()), null)).toList();
    }
}
