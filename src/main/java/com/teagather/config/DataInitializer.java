package com.teagather.config;

import com.teagather.member.constant.MemberRole;
import com.teagather.member.entity.Member;
import com.teagather.member.repository.MemberRepository;
import com.teagather.order.constant.OrderStatus;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.entity.OrderItem;
import com.teagather.order.repository.OrderRepository;
import com.teagather.order.service.OrderService;
import com.teagather.product.constant.DiscountSource;
import com.teagather.product.constant.DiscountType;
import com.teagather.product.constant.ProductStatus;
import com.teagather.product.entity.*;
import com.teagather.product.repository.CategoryRepository;
import com.teagather.product.repository.DiscountRepository;
import com.teagather.product.repository.OptionGroupRepository;
import com.teagather.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

// 처음 실행할 때 만드는 데이터
//  - 관리자 계정 (app.admin.*) : 없으면 항상 만든다
//  - 예시 데이터 (app.seed.enabled) : 메뉴가 하나도 없을 때만
//    카테고리 6 · 메뉴 26 + 신메뉴 후보 4 · 옵션 그룹 5 · 고객 160명 · 최근 90일 주문
//    고객 성향(아침 커피·디저트·차·주말 가족·가끔)과 메뉴별 상승/하락 추세, 지난 할인 효과가 들어 있어
//    PyTorch 분석 결과가 의미 있게 나온다. 난수 시드를 고정해 늘 같은 데이터가 만들어진다.
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final MemberRepository memberRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final OptionGroupRepository optionGroupRepository;
    private final DiscountRepository discountRepository;
    private final OrderRepository orderRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.username:admin}")
    private String adminUsername;
    @Value("${app.admin.password:admin1234}")
    private String adminPassword;
    @Value("${app.seed.enabled:true}")
    private boolean seedEnabled;
    @Value("${app.seed.customer-password:1234}")
    private String customerPassword;

    private static final int CUSTOMERS = 160;
    private static final int DAYS = 90;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (memberRepository.findByUsername(adminUsername).isEmpty()) {
            memberRepository.save(Member.builder().username(adminUsername).password(passwordEncoder.encode(adminPassword))
                    .name("관리자").role(MemberRole.ADMIN).created(LocalDateTime.now()).build());
            log.info("=======> 관리자 계정 생성 : {}", adminUsername);
        }
        if (seedEnabled && productRepository.count() == 0) {
            long t0 = System.currentTimeMillis();
            seed();
            log.info("=======> 예시 데이터 생성 완료 ({}ms)", System.currentTimeMillis() - t0);
        }
    }

    // ---------------------------------------------------------------- 메뉴 정의
    // 이름, 카테고리 번호, 가격, 원가, 태그, 인기(기본 가중치), 추세(90일 동안 변화율), 출시(며칠 전), 설명
    private record Def(String name, int cat, int price, int cost, String tags, double pop, double trend, int launchedDaysAgo, String desc) {}

    private static final String[][] CATS = {
            {"커피", "brown", "cup"}, {"티", "green", "tea"}, {"라떼·논커피", "cream", "latte"},
            {"에이드·주스", "orange", "ade"}, {"디저트", "pink", "cake"}, {"베이커리", "yellow", "bread"}};

    private static final List<Def> MENU = List.of(
            new Def("아메리카노", 0, 4000, 900, "쌉쌀", 10, 0.0, 400, "직접 로스팅한 원두로 내린 깔끔한 아메리카노"),
            new Def("카페 라떼", 0, 4800, 1400, "우유,고소", 7, 0.05, 400, "에스프레소와 부드러운 우유"),
            new Def("바닐라 라떼", 0, 5300, 1600, "우유,달콤", 5, -0.1, 400, "마다가스카르 바닐라 시럽을 넣은 라떼"),
            new Def("카페 모카", 0, 5500, 1500, "우유,초코,달콤", 3.2, -0.65, 400, "진한 초콜릿과 에스프레소"),
            new Def("콜드브루", 0, 5000, 1100, "쌉쌀", 4, 0.7, 300, "14시간 천천히 우려낸 콜드브루"),
            new Def("디카페인 아메리카노", 0, 4500, 1100, "디카페인,쌉쌀", 2, 0.2, 300, "카페인을 덜어낸 아메리카노"),
            new Def("아인슈페너", 0, 5800, 1700, "우유,달콤", 3.5, 0.4, 12, "달콤한 크림을 올린 비엔나 커피"),
            new Def("얼그레이 티", 1, 4500, 700, "차", 2.4, -0.1, 400, "베르가못 향의 클래식 얼그레이"),
            new Def("캐모마일 티", 1, 4500, 700, "차,디카페인", 2, 0.1, 400, "잠들기 전 편안한 허브티"),
            new Def("유자 민트 티", 1, 5200, 1100, "차,상큼,과일", 2.5, 0.9, 400, "국산 유자청과 민트를 블렌딩"),
            new Def("밀크티", 1, 5500, 1500, "차,우유,달콤", 3.5, 0.2, 400, "아쌈을 진하게 우린 로열 밀크티"),
            new Def("말차 라떼", 2, 5500, 1700, "차,우유,쌉쌀", 4, 0.8, 400, "제주 말차를 곱게 풀어낸 라떼"),
            new Def("고구마 라떼", 2, 5300, 1500, "우유,고소,달콤", 2.2, -0.5, 400, "구운 고구마를 갈아 넣은 라떼"),
            new Def("초코 라떼", 2, 5000, 1300, "우유,초코,달콤", 3, 0.0, 400, "벨기에 초콜릿 라떼"),
            new Def("딸기 라떼", 2, 5800, 1900, "우유,과일,시즌,달콤", 3, 1.2, 400, "생딸기 청을 듬뿍 넣은 시즌 라떼"),
            new Def("레몬 에이드", 3, 5300, 1100, "과일,상큼", 2.6, -0.7, 400, "직접 담근 레몬청 에이드"),
            new Def("자몽 에이드", 3, 5500, 1300, "과일,상큼", 2, -0.6, 400, "쌉싸름한 자몽 에이드"),
            new Def("청포도 에이드", 3, 5500, 1300, "과일,상큼,달콤", 1.5, -0.55, 400, "샤인머스캣 청포도 에이드"),
            new Def("바스크 치즈케이크", 4, 6500, 2300, "디저트,달콤", 3, 0.1, 400, "겉은 그을리고 속은 부드러운 치즈케이크"),
            new Def("티라미수", 4, 6800, 2500, "디저트,달콤,쌉쌀", 2.4, 0.0, 400, "마스카포네 크림과 에스프레소 시트"),
            new Def("마카롱", 4, 2500, 600, "디저트,달콤", 3, -0.6, 400, "매일 굽는 수제 마카롱 (맛 랜덤)"),
            new Def("초코 브라우니", 4, 4500, 1300, "디저트,초코,달콤", 2, -0.1, 400, "꾸덕한 다크 초콜릿 브라우니"),
            new Def("버터 크루아상", 5, 3800, 1100, "고소", 4, 0.0, 400, "프랑스 버터로 겹겹이 구운 크루아상"),
            new Def("소금빵", 5, 3500, 900, "고소", 5, 0.6, 400, "겉은 바삭, 속은 버터 향 가득"),
            new Def("스콘", 5, 3800, 900, "고소,달콤", 2, -0.45, 400, "클로티드 크림과 잼을 곁들인 스콘"),
            new Def("바나나 브레드", 5, 4200, 1200, "과일,달콤", 1.5, 0.0, 400, "잘 익은 바나나로 촉촉하게"));

    private static final List<Def> CANDIDATES = List.of(
            new Def("흑임자 라떼", 2, 5800, 1700, "우유,고소,시즌", 0, 0, 0, "고소한 흑임자를 곱게 갈아 넣은 겨울 라떼"),
            new Def("딸기 말차 라떼", 2, 6200, 2000, "차,우유,과일,시즌,달콤", 0, 0, 0, "딸기 청 위에 말차를 층층이"),
            new Def("레몬 마들렌", 4, 3200, 900, "디저트,상큼,달콤", 0, 0, 0, "레몬 글레이즈를 입힌 마들렌"),
            new Def("얼그레이 크림 콜드브루", 0, 6000, 1600, "차,우유,쌉쌀", 0, 0, 0, "얼그레이 크림을 올린 콜드브루"));

    // 고객 성향 : 하루 방문 확률(평일/주말), 시간대, 선호 태그·카테고리, 한 번에 사는 수
    private enum Persona { MORNING, DESSERT, TEA, WEEKEND, LIGHT }

    private final Random rnd = new Random(20261124L);

    private void seed() {
        LocalDate today = LocalDate.now();

        // 카테고리
        List<Category> cats = new ArrayList<>();
        for (int i = 0; i < CATS.length; i++) {
            cats.add(categoryRepository.save(Category.builder().name(CATS[i][0]).tone(CATS[i][1]).icon(CATS[i][2]).sortOrder(i + 1).build()));
        }

        // 옵션
        OptionGroup temp = group("온도", true, false, 1, new String[]{"HOT", "ICE"}, new int[]{0, 0});
        OptionGroup size = group("사이즈", true, false, 2, new String[]{"레귤러", "라지"}, new int[]{0, 500});
        OptionGroup shot = group("샷 추가", false, true, 3, new String[]{"샷 추가", "연하게"}, new int[]{500, 0});
        OptionGroup syrup = group("시럽", false, true, 4, new String[]{"바닐라 시럽", "헤이즐넛 시럽"}, new int[]{300, 300});
        OptionGroup sweet = group("당도", false, false, 5, new String[]{"30%", "50%", "100%"}, new int[]{0, 0, 0});

        // 메뉴
        List<Product> menu = new ArrayList<>();
        int sort = 0;
        for (Def d : MENU) {
            Product p = product(d, cats.get(d.cat()), ProductStatus.ON_SALE, today.minusDays(d.launchedDaysAgo()), ++sort);
            switch (d.cat()) {
                case 0 -> p.getOptionGroups().addAll(List.of(temp, size, shot, syrup));
                case 1 -> p.getOptionGroups().addAll(List.of(temp, size));
                case 2 -> p.getOptionGroups().addAll(List.of(temp, size, sweet));
                case 3 -> p.getOptionGroups().addAll(List.of(size, sweet));
                default -> { }
            }
            menu.add(productRepository.save(p));
        }
        for (Def d : CANDIDATES) {
            Product p = product(d, cats.get(d.cat()), ProductStatus.CANDIDATE, null, ++sort);
            if (d.cat() == 0 || d.cat() == 2) p.getOptionGroups().addAll(List.of(temp, size));
            productRepository.save(p);
        }
        Map<String, Product> byName = new HashMap<>();
        menu.forEach(p -> byName.put(p.getName(), p));

        // 화면이 비지 않도록 처음 표시 (분석 결과로 바꿀 수 있다)
        byName.get("소금빵").setTodayMenu(true);
        byName.get("말차 라떼").setTodayMenu(true);
        byName.get("아인슈페너").setNewMenu(true);

        // 할인 : 지난 할인 3개 (분석의 할인 효과 계산용) + 지금 진행 중 1개
        discount(byName.get("카페 모카"), "겨울 맞이 할인", 15, today.minusDays(40), today.minusDays(34));
        discount(byName.get("레몬 에이드"), "에이드 데이", 20, today.minusDays(60), today.minusDays(53));
        discount(byName.get("마카롱"), "마카롱 위크", 10, today.minusDays(25), today.minusDays(20));
        discount(byName.get("스콘"), "오늘의 할인", 15, today, today.plusDays(2));

        // 고객
        String[] last = {"김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오", "서", "신", "권", "황", "안", "송", "류", "홍"};
        String[] first = {"민준", "서연", "도윤", "지우", "하준", "서윤", "시우", "하은", "지호", "수아", "예준", "지아", "유진", "다은",
                "현우", "채원", "준서", "소율", "은우", "윤서", "지민", "가은", "태윤", "예린", "주원", "나연", "건우", "수빈", "우진", "하린"};
        String pw = passwordEncoder.encode(customerPassword);
        List<Member> customers = new ArrayList<>();
        Map<Long, Persona> personaOf = new HashMap<>();
        Map<Long, LocalDate> churnOf = new HashMap<>();
        for (int i = 1; i <= CUSTOMERS; i++) {
            Persona persona = pickPersona();
            int signupAgo = rnd.nextDouble() < 0.14 ? 2 + rnd.nextInt(18) : 25 + rnd.nextInt(300);
            Member m = Member.builder()
                    .username("customer" + i).password(pw)
                    .name(last[rnd.nextInt(last.length)] + first[rnd.nextInt(first.length)])
                    .phone(String.format("010-%04d-%04d", 1000 + rnd.nextInt(9000), 1000 + rnd.nextInt(9000)))
                    .role(MemberRole.CUSTOMER).marketingAgree(rnd.nextDouble() < 0.6)
                    .created(today.minusDays(signupAgo).atTime(9 + rnd.nextInt(12), rnd.nextInt(60)))
                    .build();
            m = memberRepository.save(m);
            customers.add(m);
            personaOf.put(m.getId(), persona);
            // 22% 는 30~75일 전부터 발길을 끊었다
            if (signupAgo > 60 && rnd.nextDouble() < 0.22) churnOf.put(m.getId(), today.minusDays(30 + rnd.nextInt(46)));
        }

        // 주문 (90일 전 ~ 오늘 지금까지)
        List<Discount> discounts = discountRepository.findAll();
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter noDay = DateTimeFormatter.ofPattern("yyMMdd");
        List<CustomerOrder> batch = new ArrayList<>();
        int total = 0;
        for (int dayIdx = DAYS; dayIdx >= 0; dayIdx--) {
            LocalDate day = today.minusDays(dayIdx);
            List<CustomerOrder> dayOrders = new ArrayList<>();
            boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            for (Member m : customers) {
                LocalDate signup = m.getCreated().toLocalDate();
                if (day.isBefore(signup)) continue;
                LocalDate churn = churnOf.get(m.getId());
                if (churn != null && !day.isBefore(churn)) continue;
                Persona ps = personaOf.get(m.getId());
                double p = visitRate(ps, weekend) * (day.getDayOfWeek() == DayOfWeek.FRIDAY ? 1.15 : 1.0);
                if (rnd.nextDouble() >= p) continue;
                int hour = hourOf(ps, weekend);
                LocalDateTime at = day.atTime(hour, rnd.nextInt(60), rnd.nextInt(60));
                if (at.isAfter(now)) continue;
                CustomerOrder o = CustomerOrder.builder().member(m)
                        .pickupType(rnd.nextDouble() < 0.55 ? PickupType.TAKEOUT : PickupType.STORE)
                        .orderedAt(at).build();
                int lines = itemCount(ps, weekend);
                Set<Long> used = new HashSet<>();
                for (int k = 0; k < lines; k++) {
                    Product prod = pickProduct(menu, ps, dayIdx, day, discounts, k > 0);
                    if (prod == null || !used.add(prod.getId())) continue;
                    o.addItem(item(prod, day, discounts, ps));
                }
                if (o.getItems().isEmpty()) continue;
                OrderService.fillAmounts(o);
                boolean isToday = dayIdx == 0;
                if (!isToday) o.setStatus(rnd.nextDouble() < 0.02 ? OrderStatus.CANCELED : OrderStatus.COMPLETED);
                else if (at.isAfter(now.minusMinutes(20))) o.setStatus(OrderStatus.PLACED);
                else if (at.isAfter(now.minusMinutes(45))) o.setStatus(OrderStatus.PREPARING);
                else o.setStatus(OrderStatus.COMPLETED);
                dayOrders.add(o);
            }
            dayOrders.sort(Comparator.comparing(CustomerOrder::getOrderedAt));
            int n = 0;
            for (CustomerOrder o : dayOrders) {
                o.setOrderNo(day.format(noDay) + "-" + String.format("%04d", ++n));
                if (o.getStatus() != OrderStatus.CANCELED) OrderService.applyToMember(o.getMember(), o, +1);
            }
            batch.addAll(dayOrders);
            total += dayOrders.size();
            if (batch.size() > 400) {
                orderRepository.saveAll(batch);
                orderRepository.flush();
                batch.clear();
            }
        }
        orderRepository.saveAll(batch);
        memberRepository.saveAll(customers);
        log.info("=======> 예시 주문 {}건, 고객 {}명, 메뉴 {}개 (+후보 {}개)", total, customers.size(), menu.size(), CANDIDATES.size());
    }

    // ---------------------------------------------------------------- 도우미
    private OptionGroup group(String name, boolean required, boolean multiple, int sort, String[] names, int[] prices) {
        OptionGroup g = OptionGroup.builder().name(name).required(required).multiple(multiple).sortOrder(sort).build();
        for (int i = 0; i < names.length; i++) {
            g.getItems().add(OptionItem.builder().group(g).name(names[i]).extraPrice(prices[i]).sortOrder(i + 1).build());
        }
        return optionGroupRepository.save(g);
    }

    private Product product(Def d, Category c, ProductStatus status, LocalDate launched, int sort) {
        return Product.builder().name(d.name()).category(c).price(d.price()).cost(d.cost()).tags(d.tags())
                .description(d.desc()).status(status).launchedAt(launched).sortOrder(sort)
                .created(LocalDateTime.now()).build();
    }

    private void discount(Product p, String name, int rate, LocalDate start, LocalDate end) {
        discountRepository.save(Discount.builder().name(name).product(p).type(DiscountType.RATE).value(rate)
                .startDate(start).endDate(end).active(true).source(DiscountSource.MANUAL).created(LocalDateTime.now()).build());
    }

    private Persona pickPersona() {
        double r = rnd.nextDouble();
        if (r < 0.28) return Persona.MORNING;
        if (r < 0.48) return Persona.DESSERT;
        if (r < 0.63) return Persona.TEA;
        if (r < 0.78) return Persona.WEEKEND;
        return Persona.LIGHT;
    }

    private double visitRate(Persona p, boolean weekend) {
        return switch (p) {
            case MORNING -> weekend ? 0.08 : 0.42;
            case DESSERT -> weekend ? 0.22 : 0.14;
            case TEA -> 0.16;
            case WEEKEND -> weekend ? 0.45 : 0.03;
            case LIGHT -> 0.045;
        };
    }

    private int hourOf(Persona p, boolean weekend) {
        return switch (p) {
            case MORNING -> 7 + rnd.nextInt(3);
            case DESSERT -> 13 + rnd.nextInt(5);
            case TEA -> rnd.nextBoolean() ? 10 + rnd.nextInt(3) : 19 + rnd.nextInt(3);
            case WEEKEND -> 11 + rnd.nextInt(6);
            case LIGHT -> 9 + rnd.nextInt(12);
        };
    }

    private int itemCount(Persona p, boolean weekend) {
        return switch (p) {
            case MORNING -> rnd.nextDouble() < 0.45 ? 2 : 1;
            case DESSERT -> 2 + (rnd.nextDouble() < 0.3 ? 1 : 0);
            case WEEKEND -> 3 + rnd.nextInt(3);
            default -> 1 + (rnd.nextDouble() < 0.35 ? 1 : 0);
        };
    }

    // 성향 × 메뉴 궁합
    private double affinity(Persona ps, Product p, boolean secondItem) {
        String cat = p.getCategory().getName();
        List<String> tags = p.getTagList();
        boolean food = cat.equals("디저트") || cat.equals("베이커리");
        double a = switch (ps) {
            case MORNING -> (cat.equals("커피") ? 3.0 : 0.4) * (secondItem ? (cat.equals("베이커리") ? 6 : 0.3) : (food ? 0.1 : 1));
            case DESSERT -> (tags.contains("달콤") ? 2.2 : 0.6) * (secondItem ? (food ? 4 : 0.3) : (food ? 0.3 : 1.2));
            case TEA -> (tags.contains("차") ? 4.0 : 0.3) * (food ? (secondItem ? 2.5 : 0.2) : 1);
            case WEEKEND -> (tags.contains("과일") || tags.contains("초코") ? 1.8 : 1.0) * (secondItem && food ? 2 : 1);
            case LIGHT -> 1.0;
        };
        if (!secondItem && food) a *= 0.25;
        return a;
    }

    private Product pickProduct(List<Product> menu, Persona ps, int dayIdx, LocalDate day, List<Discount> discounts, boolean secondItem) {
        double[] w = new double[menu.size()];
        double sum = 0;
        double t = (DAYS - dayIdx) / (double) DAYS;            // 0 = 90일 전 … 1 = 오늘
        for (int i = 0; i < menu.size(); i++) {
            Product p = menu.get(i);
            Def d = MENU.get(i);
            if (p.getLaunchedAt() != null && day.isBefore(p.getLaunchedAt())) continue;
            double trend = Math.max(0.05, 1 + d.trend() * (t - 0.5));
            double disc = discounts.stream().anyMatch(x -> x.getProduct().getId().equals(p.getId()) && x.isOn(day)) ? 1.7 : 1.0;
            double weekendBoost = (day.getDayOfWeek().getValue() >= 6 && p.getCategory().getName().equals("디저트")) ? 1.3 : 1.0;
            w[i] = d.pop() * trend * disc * weekendBoost * affinity(ps, p, secondItem);
            sum += w[i];
        }
        if (sum <= 0) return null;
        double r = rnd.nextDouble() * sum;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r <= 0) return menu.get(i);
        }
        return menu.get(menu.size() - 1);
    }

    private OrderItem item(Product p, LocalDate day, List<Discount> discounts, Persona ps) {
        List<String> opts = new ArrayList<>();
        int extra = 0;
        for (OptionGroup g : p.getOptionGroups()) {
            List<OptionItem> items = g.getItems();
            switch (g.getName()) {
                case "온도" -> opts.add(items.get(rnd.nextDouble() < (day.getMonthValue() >= 11 || day.getMonthValue() <= 2 ? 0.45 : 0.25) ? 0 : 1).getName());
                case "사이즈" -> { OptionItem it = items.get(rnd.nextDouble() < 0.3 ? 1 : 0); opts.add(it.getName()); extra += it.getExtraPrice(); }
                case "샷 추가" -> { if (rnd.nextDouble() < 0.15) { opts.add(items.get(0).getName()); extra += items.get(0).getExtraPrice(); } }
                case "시럽" -> { if (rnd.nextDouble() < 0.1) { OptionItem it = items.get(rnd.nextInt(items.size())); opts.add(it.getName()); extra += it.getExtraPrice(); } }
                case "당도" -> { if (rnd.nextDouble() < 0.3) opts.add(items.get(rnd.nextInt(items.size())).getName()); }
                default -> { }
            }
        }
        int qty = ps == Persona.WEEKEND && rnd.nextDouble() < 0.3 ? 2 : 1;
        int unit = p.getPrice() + extra;
        int unitDisc = discounts.stream().filter(x -> x.getProduct().getId().equals(p.getId()) && x.isOn(day))
                .mapToInt(x -> x.amountFor(p.getPrice())).max().orElse(0);
        return OrderItem.builder().product(p).productName(p.getName()).optionText(String.join(" · ", opts))
                .unitPrice(unit).quantity(qty).discountAmount(unitDisc * qty).lineAmount((unit - unitDisc) * qty).build();
    }
}
