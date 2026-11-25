package com.teagather.mysql;

import com.teagather.member.constant.MemberRole;
import com.teagather.member.entity.Member;
import com.teagather.member.repository.MemberRepository;
import com.teagather.order.cart.Cart;
import com.teagather.order.constant.OrderStatus;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.repository.OrderDaySeqRepository;
import com.teagather.order.repository.OrderItemRepository;
import com.teagather.order.repository.OrderRepository;
import com.teagather.order.service.OrderPlacementFacade;
import com.teagather.order.service.OrderService;
import com.teagather.product.constant.ProductStatus;
import com.teagather.product.entity.Category;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.CategoryRepository;
import com.teagather.product.repository.ProductRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// MySQL 8.4 (InnoDB, REPEATABLE READ) 실DB 검증 — 리뷰 지적 #2
//  H2 는 MODE=MySQL 이어도 잠금 동작(gap lock·next-key lock)을 흉내 내지 않는다
//  → "그날 첫 주문이 동시에 몰릴 때" INSERT ... ON DUPLICATE KEY UPDATE 카운터가 교착·중복 없이 도는지 실제 InnoDB 로 본다
//  Docker 가 없으면 건너뛴다. 실행 : ./gradlew mysqlTest
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("mysqltest")
class MySqlOrderDefenseIT {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    static final int N = 50;

    @Autowired OrderPlacementFacade facade;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderItemRepository orderItemRepository;
    @Autowired OrderDaySeqRepository daySeqRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired PlatformTransactionManager txManager;
    @Autowired MeterRegistry registry;

    Product product;

    @BeforeEach
    void prepare() {
        cleanUp();
        Category c = categoryRepository.save(Category.builder().name("my-" + UUID.randomUUID().toString().substring(0, 8))
                .sortOrder(1).tone("brown").icon("cup").build());
        product = productRepository.save(Product.builder().name("MySQL 검증 라떼").category(c)
                .price(5000).cost(1500).status(ProductStatus.ON_SALE).sortOrder(1)
                .created(LocalDateTime.now()).build());
    }

    @AfterEach
    void after() {
        cleanUp();
    }

    // (a) 그날 첫 주문 : 카운터 줄이 없는 상태에서 50건이 한꺼번에 → 모두 성공, 번호 1..50 연속
    @RepeatedTest(5)
    @DisplayName("MySQL - 그날 첫 주문 동시 50건 : 실패 0, 번호 중복·누락 0")
    void firstOrdersOfDay() throws Exception {
        Result r = placeConcurrently(N);
        report("first-of-day", r);
        assertThat(r.failures).as("사용자에게 보이는 실패 (유형 %s)", r.failureTypes).isZero();
        assertContiguous(r.numbers, 1, N);
    }

    // (b) 카운터 줄 없이 오늘 주문이 이미 있는 경우 (이전 방식으로 만든 주문) → 기존 마지막 번호부터 이어서
    @RepeatedTest(5)
    @DisplayName("MySQL - 오늘 기존 주문 3건 + 카운터 없음 + 동시 50건 : 4..53 연속")
    void seedFromExistingOrders() throws Exception {
        Member early = newCustomer("my-early");
        for (int i = 0; i < 3; i++) orderService.place(early, cartWithOneLine(), PickupType.TAKEOUT, null);
        new TransactionTemplate(txManager).executeWithoutResult(s -> daySeqRepository.deleteAllInBatch());   // 카운터 도입 전 상태로

        Result r = placeConcurrently(N);
        report("seed-path", r);
        assertThat(r.failures).as("사용자에게 보이는 실패 (유형 %s)", r.failureTypes).isZero();
        List<String> all = orderRepository.findAll().stream().map(CustomerOrder::getOrderNo).toList();
        assertContiguous(all, 1, N + 3);
    }

    // (c) 같은 고객이 동시에 주문 10 + 취소 10 → 회원 집계 = 취소 안 된 주문 합계
    @RepeatedTest(5)
    @DisplayName("MySQL - 같은 고객 동시 주문 10 + 취소 10 : 회원 집계 정확")
    void memberAggregates() throws Exception {
        Member m = newCustomer("my-agg");
        List<Long> toCancel = new ArrayList<>();
        for (int i = 0; i < 10; i++) toCancel.add(facade.place(m.getUsername(), cartWithOneLine(), PickupType.TAKEOUT, null).getId());

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger failed = new AtomicInteger();
        Map<String, AtomicInteger> types = new ConcurrentHashMap<>();
        try {
            for (Long id : toCancel) {
                futures.add(pool.submit(() -> run(start, failed, types,
                        () -> orderService.cancelByCustomer(id, memberRepository.findById(m.getId()).orElseThrow()))));
            }
            for (int i = 0; i < 10; i++) {
                Cart cart = cartWithOneLine();
                futures.add(pool.submit(() -> run(start, failed, types,
                        () -> facade.place(m.getUsername(), cart, PickupType.TAKEOUT, null))));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(90, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Member after = memberRepository.findById(m.getId()).orElseThrow();
        List<CustomerOrder> valid = orderRepository.findAll().stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELED).toList();
        long spent = valid.stream().mapToLong(CustomerOrder::getPayAmount).sum();
        int point = valid.stream().mapToInt(CustomerOrder::getEarnedPoint).sum();
        System.out.printf("[MYSQL] aggregates failed=%d types=%s orderCount %d(exp %d) totalSpent %d(exp %d) point %d(exp %d)%n",
                failed.get(), types, after.getOrderCount(), valid.size(), after.getTotalSpent(), spent, after.getPoint(), point);

        assertThat(failed.get()).as("실패 (유형 %s)", types).isZero();
        assertThat(after.getOrderCount()).isEqualTo(valid.size());
        assertThat(after.getTotalSpent()).isEqualTo(spent);
        assertThat(after.getPoint()).isEqualTo(point);
    }

    // ===== 도우미 =====

    record Result(int failures, Map<String, AtomicInteger> failureTypes, List<String> numbers,
                  double retries, double conflictOrderNo, double conflictLock, long millis) {}

    private Result placeConcurrently(int n) throws Exception {
        List<String> users = new ArrayList<>();
        List<Cart> carts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            users.add(newCustomer("my-c" + i).getUsername());
            carts.add(cartWithOneLine());
        }
        double retry0 = count("order.place.retry", null), on0 = count("order.place.conflict", "order_no"),
                lk0 = count("order.place.conflict", "lock");

        ExecutorService pool = Executors.newFixedThreadPool(n);     // 50건을 정말 한꺼번에
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failed = new AtomicInteger();
        Map<String, AtomicInteger> types = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        try {
            for (int i = 0; i < n; i++) {
                String u = users.get(i);
                Cart cart = carts.get(i);
                futures.add(pool.submit(() -> run(start, failed, types,
                        () -> facade.place(u, cart, PickupType.TAKEOUT, null))));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(90, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        long ms = System.currentTimeMillis() - t0;
        List<String> numbers = orderRepository.findAll().stream().map(CustomerOrder::getOrderNo).toList();
        return new Result(failed.get(), types, numbers,
                count("order.place.retry", null) - retry0,
                count("order.place.conflict", "order_no") - on0,
                count("order.place.conflict", "lock") - lk0, ms);
    }

    private void run(CountDownLatch start, AtomicInteger failed, Map<String, AtomicInteger> types, Runnable body) {
        try {
            start.await();
            body.run();
        } catch (Exception e) {
            failed.incrementAndGet();
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String key = e.getClass().getSimpleName() + "/" + root.getClass().getSimpleName() + ":" + String.valueOf(root.getMessage());
            types.computeIfAbsent(key.length() > 160 ? key.substring(0, 160) : key, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    private double count(String name, String cause) {
        return registry.find(name).counters().stream()
                .filter(c -> cause == null || cause.equals(c.getId().getTag("cause")))
                .mapToDouble(Counter::count).sum();
    }

    private void report(String label, Result r) {
        System.out.printf("[MYSQL] %s failures=%d types=%s saved=%d retries=%.0f conflict(order_no)=%.0f conflict(lock)=%.0f ms=%d%n",
                label, r.failures, r.failureTypes, r.numbers.size(), r.retries, r.conflictOrderNo, r.conflictLock, r.millis);
    }

    private void assertContiguous(List<String> numbers, int from, int to) {
        assertThat(numbers).as("주문번호 중복").doesNotHaveDuplicates();
        List<Integer> seq = numbers.stream().map(s -> Integer.parseInt(s.substring(s.lastIndexOf('-') + 1))).sorted().toList();
        assertThat(seq).as("번호 누락 없이 연속").containsExactlyElementsOf(IntStream.rangeClosed(from, to).boxed().toList());
    }

    private Member newCustomer(String username) {
        return memberRepository.save(Member.builder().username(username).password("{noop}1234").name(username)
                .role(MemberRole.CUSTOMER).created(LocalDateTime.now()).build());
    }

    private Cart cartWithOneLine() {
        Cart cart = new Cart();
        cart.add(orderService.makeLine(product.getId(), List.of(), 1));
        return cart;
    }

    private void cleanUp() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            orderItemRepository.deleteAllInBatch();
            orderRepository.deleteAllInBatch();
            daySeqRepository.deleteAllInBatch();
            memberRepository.deleteAll(memberRepository.findByRole(MemberRole.CUSTOMER));
            productRepository.deleteAll();
            categoryRepository.deleteAll();
        });
    }
}
