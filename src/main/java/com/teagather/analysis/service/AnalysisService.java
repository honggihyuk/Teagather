package com.teagather.analysis.service;

import com.teagather.analysis.constant.AnalysisStatus;
import com.teagather.analysis.constant.InsightKind;
import com.teagather.analysis.dto.AnalysisInput;
import com.teagather.analysis.dto.AnalysisResult;
import com.teagather.analysis.entity.*;
import com.teagather.analysis.repository.*;
import com.teagather.common.exception.BusinessException;
import com.teagather.member.constant.MemberRole;
import com.teagather.member.entity.Member;
import com.teagather.member.repository.MemberRepository;
import com.teagather.order.repository.OrderItemRepository;
import com.teagather.product.constant.DiscountSource;
import com.teagather.product.constant.DiscountType;
import com.teagather.product.constant.ProductStatus;
import com.teagather.product.entity.Discount;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.CategoryRepository;
import com.teagather.product.repository.DiscountRepository;
import com.teagather.product.repository.ProductRepository;
import com.teagather.product.service.DiscountService;
import com.teagather.product.service.ProductService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

// 분석 실행 · 결과 저장 · 결과 적용
//  1) 주문·메뉴·고객을 AnalysisInput 으로 모은다 (최근 120일)
//  2) PyTorch 스크립트 실행 → 실패하면 Java 대체 계산
//  3) 고객군·이탈 위험(회원), 추천, 메뉴 지표, 할인/오늘의 메뉴/신메뉴 후보를 저장
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final AnalysisRunRepository runRepository;
    private final SegmentSummaryRepository segmentRepository;
    private final MenuMetricRepository metricRepository;
    private final MenuInsightRepository insightRepository;
    private final RecommendationRepository recommendationRepository;
    private final MemberRepository memberRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final DiscountRepository discountRepository;
    private final OrderItemRepository orderItemRepository;
    private final DiscountService discountService;
    private final ProductService productService;
    private final PythonAnalysisRunner pythonRunner;
    private final JavaFallbackAnalyzer fallback;
    private final PlatformTransactionManager txManager;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public boolean isRunning() {
        return running.get();
    }

    // 분석 한 번 (이미 돌고 있으면 건너뛴다). 오래 걸리므로 AnalysisLauncher 가 비동기로 부른다
    public Long run(String triggeredBy) {
        if (!running.compareAndSet(false, true)) {
            log.info("=======> 분석이 이미 돌고 있어 건너뜀 ({})", triggeredBy);
            return null;
        }
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long runId = null;
        try {
            AnalysisRun run = tx.execute(s -> runRepository.save(AnalysisRun.builder()
                    .startedAt(LocalDateTime.now()).status(AnalysisStatus.RUNNING).triggeredBy(triggeredBy).build()));
            runId = run.getId();
            AnalysisInput input = tx.execute(s -> buildInput(LocalDate.now()));

            AnalysisResult result;
            String note = null;
            try {
                result = pythonRunner.run(input);
            } catch (Exception e) {
                log.warn("=======> PyTorch 분석 실패, Java 대체 계산으로 진행 : {}", e.getMessage());
                note = "PyTorch 를 쓰지 못해 대체 계산으로 만들었어요 : " + e.getMessage();
                result = fallback.analyze(input);
            }
            final AnalysisResult res = result;
            final String msg = note;
            final Long id = runId;
            tx.executeWithoutResult(s -> save(id, input, res, msg));
            log.info("=======> 분석 완료 #{} ({})", runId, result.engine());
            return runId;
        } catch (Exception e) {
            log.error("=======> 분석 실패", e);
            if (runId != null) {
                final Long id = runId;
                tx.executeWithoutResult(s -> runRepository.findById(id).ifPresent(r -> {
                    r.setStatus(AnalysisStatus.FAILED);
                    r.setFinishedAt(LocalDateTime.now());
                    String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    r.setMessage(m.length() > 1000 ? m.substring(0, 1000) : m);
                }));
            }
            return runId;
        } finally {
            running.set(false);
        }
    }

    // ===== 1. 입력 만들기 =====
    AnalysisInput buildInput(LocalDate today) {
        LocalDate from = today.minusDays(120);
        Set<Long> discountedNow = discountService.activeMap(today).keySet();
        List<AnalysisInput.Cat> cats = categoryRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(c -> new AnalysisInput.Cat(c.getId(), c.getName())).toList();
        List<AnalysisInput.Prod> prods = productRepository.findAllOrdered().stream()
                .filter(p -> p.getStatus() != ProductStatus.HIDDEN && p.getCategory() != null)
                .map(p -> new AnalysisInput.Prod(p.getId(), p.getName(), p.getCategory().getId(), p.getCategory().getName(),
                        p.getPrice(), p.getCost(), p.getTagList(), p.getStatus().name(), discountedNow.contains(p.getId()),
                        p.getLaunchedAt() == null ? null : p.getLaunchedAt().toString()))
                .toList();
        List<AnalysisInput.Cust> custs = memberRepository.findByRole(MemberRole.CUSTOMER).stream()
                .filter(Member::isActive)
                .map(m -> new AnalysisInput.Cust(m.getId(), (m.getCreated() == null ? today : m.getCreated().toLocalDate()).toString()))
                .toList();
        Set<Long> custIds = custs.stream().map(AnalysisInput.Cust::id).collect(Collectors.toSet());

        // [주문 id, 고객 id, 주문 시각, 상품 id, 수량, 줄 금액, 할인]
        Map<Long, List<Object[]>> byOrder = new LinkedHashMap<>();
        for (Object[] r : orderItemRepository.findSalesRows(from.atStartOfDay(), today.plusDays(1).atStartOfDay())) {
            if (r[1] == null || !custIds.contains((Long) r[1])) continue;
            byOrder.computeIfAbsent((Long) r[0], k -> new ArrayList<>()).add(r);
        }
        List<AnalysisInput.Ord> orders = new ArrayList<>();
        for (Map.Entry<Long, List<Object[]>> e : byOrder.entrySet()) {
            Object[] first = e.getValue().get(0);
            LocalDateTime at = (LocalDateTime) first[2];
            List<AnalysisInput.Line> lines = e.getValue().stream().map(r -> new AnalysisInput.Line((Long) r[3],
                    ((Number) r[4]).intValue(), ((Number) r[5]).longValue(), ((Number) r[6]).intValue() > 0)).toList();
            orders.add(new AnalysisInput.Ord(e.getKey(), (Long) first[1], at.toLocalDate().toString(), at.getHour(), lines));
        }
        List<AnalysisInput.Disc> discs = discountRepository.findEndingAfter(from).stream().filter(Discount::isActive)
                .map(d -> new AnalysisInput.Disc(d.getProduct().getId(), d.getStartDate().toString(), d.getEndDate().toString()))
                .toList();
        return new AnalysisInput(today.toString(), cats, prods, custs, orders, discs);
    }

    // ===== 2. 결과 저장 =====
    void save(Long runId, AnalysisInput input, AnalysisResult res, String note) {
        AnalysisRun run = runRepository.findById(runId).orElseThrow();
        Map<Long, Product> products = productRepository.findAll().stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, Member> members = memberRepository.findByRole(MemberRole.CUSTOMER).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));

        int order = 0;
        for (AnalysisResult.Segment s : nz(res.segments())) {
            segmentRepository.save(SegmentSummary.builder().run(run).code(s.code()).label(s.label()).customerCount(s.count())
                    .avgOrders(s.avgOrders()).avgSpent(Math.round(s.avgSpent())).avgRecency(s.avgRecency())
                    .topCategory(cut(s.topCategory(), 20)).action(cut(s.action(), 100)).description(cut(s.description(), 200))
                    .sortOrder(order++).build());
        }
        for (AnalysisResult.CustomerOut c : nz(res.customers())) {
            Member m = members.get(c.id());
            if (m == null) continue;
            m.setSegmentCode(c.segment());
            m.setSegmentLabel(c.label());
            m.setChurnRisk(c.churnRisk());
        }
        recommendationRepository.deleteAllInBulk();
        for (AnalysisResult.RecOut r : nz(res.recommendations())) {
            Member m = members.get(r.customerId());
            Product p = products.get(r.productId());
            if (m == null || p == null) continue;
            recommendationRepository.save(Recommendation.builder().run(run).member(m).product(p)
                    .rank(r.rank()).score(r.score()).reason(cut(r.reason(), 100)).build());
        }
        for (AnalysisResult.Metric x : nz(res.metrics())) {
            Product p = products.get(x.productId());
            if (p == null) continue;
            metricRepository.save(MenuMetric.builder().run(run).product(p).qty30(x.qty30()).revenue30(x.revenue30())
                    .trendPct(x.trendPct()).forecast(x.forecast()).marginRate(x.marginRate()).affinity(x.affinity()).build());
        }
        savePicks(run, products, InsightKind.DISCOUNT, res.discount());
        savePicks(run, products, InsightKind.TODAY, res.today());
        savePicks(run, products, InsightKind.NEW, res.newMenu());

        run.setStatus(AnalysisStatus.SUCCESS);
        run.setFinishedAt(LocalDateTime.now());
        run.setEngine(cut(res.engine(), 40));
        run.setLossText(cut(res.lossText(), 500));
        run.setMessage(cut(note, 1000));
        run.setCustomerCount(input.customers().size());
        run.setOrderCount(input.orders().size());
    }

    private void savePicks(AnalysisRun run, Map<Long, Product> products, InsightKind kind, List<AnalysisResult.Pick> picks) {
        for (AnalysisResult.Pick p : nz(picks)) {
            Product prod = products.get(p.productId());
            if (prod == null) continue;
            insightRepository.save(MenuInsight.builder().run(run).product(prod).kind(kind).rank(p.rank())
                    .score(p.score()).forecast(p.forecast()).suggestedRate(p.suggestedRate()).reason(cut(p.reason(), 300)).build());
        }
    }

    // ===== 3. 화면용 조회 =====

    @Transactional(readOnly = true)
    public Optional<AnalysisRun> latestSuccess() {
        return runRepository.findFirstByStatusOrderByIdDesc(AnalysisStatus.SUCCESS);
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisRun> latest() {
        return runRepository.findFirstByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<AnalysisRun> history() {
        return runRepository.findTop10ByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public List<SegmentSummary> segments(Long runId) {
        return segmentRepository.findByRunIdOrderBySortOrderAsc(runId);
    }

    @Transactional(readOnly = true)
    public List<MenuMetric> metrics(Long runId) {
        return metricRepository.findByRun(runId);
    }

    @Transactional(readOnly = true)
    public Map<InsightKind, List<MenuInsight>> picks(Long runId) {
        Map<InsightKind, List<MenuInsight>> map = new EnumMap<>(InsightKind.class);
        for (InsightKind k : InsightKind.values()) map.put(k, new ArrayList<>());
        for (MenuInsight i : insightRepository.findByRun(runId)) map.get(i.getKind()).add(i);
        return map;
    }

    @Transactional(readOnly = true)
    public List<Member> churnTop(int n) {
        return memberRepository.findChurnTop(PageRequest.of(0, n));
    }

    // 고객 한 명의 추천 (상품 id → 이유)
    @Transactional(readOnly = true)
    public LinkedHashMap<Long, String> recommendationsFor(Long memberId) {
        LinkedHashMap<Long, String> map = new LinkedHashMap<>();
        for (Recommendation r : recommendationRepository.findByMemberIdOrderByRankAsc(memberId)) {
            map.put(r.getProduct().getId(), r.getReason());
        }
        return map;
    }

    // 많이 찾는 메뉴 : 최근 분석의 30일 판매 순 (분석이 없으면 빈 목록)
    @Transactional(readOnly = true)
    public List<Long> popularProductIds(int n) {
        return latestSuccess().map(r -> metricRepository.findByRun(r.getId()).stream()
                .filter(m -> m.getProduct().isOrderable())
                .limit(n).map(m -> m.getProduct().getId()).toList()).orElse(List.of());
    }

    // ===== 4. 적용 =====
    @Transactional
    public String apply(Long insightId) {
        MenuInsight i = insightRepository.findById(insightId).orElseThrow(() -> new EntityNotFoundException("추천을 찾을 수 없어요."));
        if (i.isApplied()) throw new BusinessException("이미 적용한 추천이에요.");
        Product p = i.getProduct();
        LocalDate today = LocalDate.now();
        String msg = switch (i.getKind()) {
            case DISCOUNT -> {
                int rate = i.getSuggestedRate() == null ? 10 : i.getSuggestedRate();
                discountService.create(p.getId(), "오늘의 할인", DiscountType.RATE, rate, today, today, DiscountSource.AI);
                yield p.getName() + " 에 오늘 " + rate + "% 할인을 걸었어요.";
            }
            case TODAY -> {
                productService.setTodayMenu(p.getId(), true);
                yield p.getName() + " 을(를) 오늘의 메뉴로 올렸어요.";
            }
            case NEW -> {
                if (p.getStatus() != ProductStatus.CANDIDATE && p.isNewMenu()) throw new BusinessException("이미 출시한 메뉴예요.");
                productService.launch(p.getId());
                yield p.getName() + " 을(를) 신메뉴로 출시했어요.";
            }
        };
        i.setApplied(true);
        i.setAppliedAt(LocalDateTime.now());
        return msg;
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String cut(String s, int n) {
        return s == null ? null : (s.length() > n ? s.substring(0, n) : s);
    }
}
