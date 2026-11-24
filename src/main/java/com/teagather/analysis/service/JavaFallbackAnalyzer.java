package com.teagather.analysis.service;

import com.teagather.analysis.dto.AnalysisInput;
import com.teagather.analysis.dto.AnalysisInput.*;
import com.teagather.analysis.dto.AnalysisResult;
import com.teagather.analysis.dto.AnalysisResult.*;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

// 파이썬·PyTorch 를 쓸 수 없을 때의 대체 계산 (규칙 기반). 결과 모양은 PyTorch 와 같다.
//  - 고객군 : 최근성·빈도·금액 규칙,  이탈 위험 : 평소 주문 간격 대비 경과일
//  - 추천 : 고객이 즐겨 사는 카테고리·태그와 겹치는 인기 메뉴
//  - 예상 판매 : 최근 4주 같은 요일 평균
@Component
public class JavaFallbackAnalyzer {

    public static final String ENGINE = "Java 대체 계산";

    public AnalysisResult analyze(AnalysisInput in) {
        LocalDate today = LocalDate.parse(in.today());
        Map<Long, Prod> prods = in.products().stream().collect(Collectors.toMap(Prod::id, p -> p, (a, b) -> a, LinkedHashMap::new));

        // ---------- 고객 ----------
        Map<Long, List<Ord>> byCust = in.orders().stream().collect(Collectors.groupingBy(Ord::customerId));
        List<CustomerOut> custOut = new ArrayList<>();
        Map<String, List<Long>> segMembers = new LinkedHashMap<>();
        Map<Long, double[]> feats = new HashMap<>();   // recency, freq90, spent90
        List<Double> spends = new ArrayList<>();
        for (Cust c : in.customers()) {
            List<Ord> os = byCust.getOrDefault(c.id(), List.of());
            LocalDate created = LocalDate.parse(c.created());
            LocalDate last = os.stream().map(o -> LocalDate.parse(o.date())).max(Comparator.naturalOrder()).orElse(null);
            double recency = last == null ? ChronoUnit.DAYS.between(created, today) + 30 : ChronoUnit.DAYS.between(last, today);
            List<Ord> recent = os.stream().filter(o -> !LocalDate.parse(o.date()).isBefore(today.minusDays(90))).toList();
            double spent = recent.stream().flatMap(o -> o.items().stream()).mapToLong(Line::amount).sum();
            feats.put(c.id(), new double[]{recency, recent.size(), spent, ChronoUnit.DAYS.between(created, today)});
            spends.add(spent);
        }
        spends.sort(Comparator.reverseOrder());
        double vipCut = spends.isEmpty() ? Double.MAX_VALUE : spends.get(Math.max(0, (int) (spends.size() * 0.15) - 1));
        for (Cust c : in.customers()) {
            double[] f = feats.get(c.id());
            String code;
            if (f[3] <= 21) code = "NEW";
            else if (f[0] > 28) code = "AT_RISK";
            else if (f[2] >= vipCut && f[2] > 0) code = "VIP";
            else if (f[1] >= 12) code = "LOYAL";
            else code = "OCCASIONAL";
            double interval = f[1] > 1 ? 90.0 / f[1] : 30;
            double risk = 1 / (1 + Math.exp(-(f[0] - 2 * interval) / 7));
            custOut.add(new CustomerOut(c.id(), code, label(code), round(risk)));
            segMembers.computeIfAbsent(code, k -> new ArrayList<>()).add(c.id());
        }
        List<Segment> segments = new ArrayList<>();
        for (String code : List.of("VIP", "LOYAL", "NEW", "OCCASIONAL", "AT_RISK")) {
            List<Long> ids = segMembers.getOrDefault(code, List.of());
            if (ids.isEmpty()) continue;
            double avgOrders = ids.stream().mapToDouble(id -> feats.get(id)[1]).average().orElse(0);
            double avgSpent = ids.stream().mapToDouble(id -> feats.get(id)[2]).average().orElse(0);
            double avgRec = ids.stream().mapToDouble(id -> Math.min(feats.get(id)[0], 120)).average().orElse(0);
            Map<String, Integer> catCount = new HashMap<>();
            for (Long id : ids) for (Ord o : byCust.getOrDefault(id, List.of())) for (Line l : o.items()) {
                Prod p = prods.get(l.productId());
                if (p != null) catCount.merge(p.category(), l.qty(), Integer::sum);
            }
            String top = catCount.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("-");
            segments.add(new Segment(code, label(code), ids.size(), round(avgOrders), Math.round(avgSpent), round(avgRec), top, action(code), description(code)));
        }

        // ---------- 메뉴 지표 ----------
        Map<Long, int[]> daily = new HashMap<>();     // 상품 → 최근 90일 일별 수량 (index 0 = 90일 전, 89 = 어제)
        Map<Long, Long> rev30 = new HashMap<>();
        for (Ord o : in.orders()) {
            LocalDate d = LocalDate.parse(o.date());
            int idx = (int) ChronoUnit.DAYS.between(today.minusDays(90), d);
            for (Line l : o.items()) {
                if (idx >= 0 && idx < 90) daily.computeIfAbsent(l.productId(), k -> new int[90])[idx] += l.qty();
                if (!d.isBefore(today.minusDays(30)) && d.isBefore(today)) rev30.merge(l.productId(), l.amount(), Long::sum);
            }
        }
        int maxQty = 1;
        Map<Long, Integer> qty30 = new HashMap<>();
        for (Prod p : prods.values()) {
            int[] s = daily.getOrDefault(p.id(), new int[90]);
            int q = 0;
            for (int i = 60; i < 90; i++) q += s[i];
            qty30.put(p.id(), q);
            maxQty = Math.max(maxQty, q);
        }
        List<Metric> metrics = new ArrayList<>();
        Map<Long, Metric> metricById = new HashMap<>();
        int dowIdx = 89 - (int) ChronoUnit.DAYS.between(today.minusDays(7), today.minusDays(1)); // 오늘과 같은 요일 = 7일 전
        for (Prod p : prods.values()) {
            int[] s = daily.getOrDefault(p.id(), new int[90]);
            double last14 = 0, prev14 = 0;
            for (int i = 76; i < 90; i++) last14 += s[i];
            for (int i = 62; i < 76; i++) prev14 += s[i];
            double trend = prev14 == 0 ? (last14 > 0 ? 100 : 0) : (last14 - prev14) / prev14 * 100;
            double fc = 0;
            for (int w = 0; w < 4; w++) fc += s[dowIdx - 7 * w];
            fc /= 4;
            double margin = p.price() == 0 ? 0 : (p.price() - p.cost()) / (double) p.price();
            Metric m = new Metric(p.id(), qty30.get(p.id()), rev30.getOrDefault(p.id(), 0L), round(trend), round(fc), round(margin),
                    round(qty30.get(p.id()) / (double) maxQty));
            if (!"CANDIDATE".equals(p.status())) metrics.add(m);
            metricById.put(p.id(), m);
        }

        // ---------- 고르기 ----------
        List<Prod> onSale = prods.values().stream().filter(p -> "ON_SALE".equals(p.status())).toList();
        String dow = today.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.KOREAN);
        double maxFc = onSale.stream().mapToDouble(p -> metricById.get(p.id()).forecast()).max().orElse(1);
        List<Pick> todayPicks = onSale.stream()
                .sorted(Comparator.comparingDouble((Prod p) -> -scoreToday(metricById.get(p.id()), maxFc)))
                .limit(3).map(new java.util.function.Function<Prod, Pick>() {
                    int r = 0;
                    public Pick apply(Prod p) {
                        Metric m = metricById.get(p.id());
                        return new Pick(p.id(), ++r, round(scoreToday(m, maxFc)), m.forecast(), null,
                                String.format("오늘(%s) 예상 %.0f개 · 2주 추세 %+.0f%%", dow, m.forecast(), m.trendPct()));
                    }
                }).toList();
        Set<Long> todayIds = todayPicks.stream().map(Pick::productId).collect(Collectors.toSet());
        List<Pick> discount = onSale.stream()
                .filter(p -> !p.discountedNow() && !todayIds.contains(p.id()))
                .filter(p -> metricById.get(p.id()).marginRate() >= 0.55 && metricById.get(p.id()).trendPct() < 0)
                .sorted(Comparator.comparingDouble(p -> metricById.get(p.id()).trendPct()))
                .limit(3).map(new java.util.function.Function<Prod, Pick>() {
                    int r = 0;
                    public Pick apply(Prod p) {
                        Metric m = metricById.get(p.id());
                        int rate = m.marginRate() >= 0.7 ? 20 : m.marginRate() >= 0.62 ? 15 : 10;
                        double score = Math.min(1, 0.4 * m.marginRate() + 0.4 * Math.min(1, -m.trendPct() / 50) + 0.2 * (1 - m.forecast() / Math.max(1, maxFc)));
                        return new Pick(p.id(), ++r, round(score), m.forecast(), rate,
                                String.format("2주 판매 %+.0f%% · 마진 %.0f%% → %d%% 할인 여유", m.trendPct(), m.marginRate() * 100, rate));
                    }
                }).toList();
        // 신메뉴 후보 : 같은 태그를 가진 판매 메뉴의 인기 평균
        List<Pick> newPicks = new ArrayList<>();
        List<Prod> cands = prods.values().stream().filter(p -> "CANDIDATE".equals(p.status())).toList();
        List<double[]> scored = new ArrayList<>();
        for (Prod c : cands) {
            double sum = 0, n = 0;
            for (Prod p : onSale) {
                long overlap = p.tags().stream().filter(c.tags()::contains).count() + (p.categoryId() == c.categoryId() ? 1 : 0);
                if (overlap > 0) { sum += overlap * metricById.get(p.id()).affinity(); n += overlap; }
            }
            scored.add(new double[]{c.id(), n == 0 ? 0 : sum / n});
        }
        scored.sort((a, b) -> Double.compare(b[1], a[1]));
        int r = 0;
        for (double[] s : scored) {
            if (r >= 3) break;
            Prod c = prods.get((long) s[0]);
            newPicks.add(new Pick(c.id(), ++r, round(s[1]), 0, null, "비슷한 태그(" + String.join("·", c.tags()) + ") 메뉴의 인기 기준"));
        }

        // ---------- 추천 ----------
        List<RecOut> recs = new ArrayList<>();
        for (Cust c : in.customers()) {
            List<Ord> os = byCust.getOrDefault(c.id(), List.of());
            if (os.isEmpty()) continue;
            Map<Long, Integer> bought = new HashMap<>();
            Map<String, Double> tagPref = new HashMap<>();
            for (Ord o : os) for (Line l : o.items()) {
                bought.merge(l.productId(), l.qty(), Integer::sum);
                Prod p = prods.get(l.productId());
                if (p != null) {
                    tagPref.merge("#" + p.category(), (double) l.qty(), Double::sum);
                    for (String t : p.tags()) tagPref.merge(t, (double) l.qty(), Double::sum);
                }
            }
            Set<Long> top2 = bought.entrySet().stream().sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                    .limit(2).map(Map.Entry::getKey).collect(Collectors.toSet());
            List<double[]> sc = new ArrayList<>();
            for (Prod p : onSale) {
                if (top2.contains(p.id())) continue;
                double s = tagPref.getOrDefault("#" + p.category(), 0.0);
                for (String t : p.tags()) s += tagPref.getOrDefault(t, 0.0);
                s = s * (0.5 + metricById.get(p.id()).affinity());
                sc.add(new double[]{p.id(), s});
            }
            sc.sort((a, b) -> Double.compare(b[1], a[1]));
            double max = sc.isEmpty() ? 1 : Math.max(1e-9, sc.get(0)[1]);
            for (int i = 0; i < Math.min(4, sc.size()); i++) {
                recs.add(new RecOut(c.id(), (long) sc.get(i)[0], i + 1, round(sc.get(i)[1] / max), "자주 고르는 맛과 비슷해요"));
            }
        }

        return new AnalysisResult(ENGINE, "규칙 기반 계산 (학습 없음)", segments, custOut, recs, metrics, discount, todayPicks, newPicks);
    }

    private static double scoreToday(Metric m, double maxFc) {
        double trend = Math.max(-1, Math.min(1, m.trendPct() / 50));
        return 0.6 * (m.forecast() / Math.max(1, maxFc)) + 0.2 * (trend + 1) / 2 + 0.2 * m.marginRate();
    }

    static String label(String code) {
        return switch (code) {
            case "VIP" -> "VIP";
            case "LOYAL" -> "단골";
            case "NEW" -> "신규";
            case "AT_RISK" -> "이탈 위험";
            default -> "가끔 방문";
        };
    }

    static String action(String code) {
        return switch (code) {
            case "VIP" -> "신메뉴 먼저 알림 · 감사 쿠폰";
            case "LOYAL" -> "스탬프 2배 이벤트";
            case "NEW" -> "두 번째 주문 할인 쿠폰";
            case "AT_RISK" -> "재방문 쿠폰 (7일 안 사용)";
            default -> "오늘의 할인 메뉴 알림";
        };
    }

    static String description(String code) {
        return switch (code) {
            case "VIP" -> "결제 금액이 가장 큰 고객";
            case "LOYAL" -> "자주, 꾸준히 오는 고객";
            case "NEW" -> "가입한 지 얼마 안 된 고객";
            case "AT_RISK" -> "한동안 주문이 없는 고객";
            default -> "가끔 들르는 고객";
        };
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    @SuppressWarnings("unused")
    private static DayOfWeek dow(LocalDate d) {
        return d.getDayOfWeek();
    }
}
