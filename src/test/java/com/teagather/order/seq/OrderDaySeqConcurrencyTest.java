package com.teagather.order.seq;

import com.teagather.member.constant.MemberRole;
import com.teagather.member.entity.Member;
import com.teagather.member.repository.MemberRepository;
import com.teagather.order.cart.Cart;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.repository.OrderDaySeqRepository;
import com.teagather.order.repository.OrderRepository;
import com.teagather.order.service.OrderService;
import com.teagather.product.constant.ProductStatus;
import com.teagather.product.entity.Category;
import com.teagather.product.entity.Product;
import com.teagather.product.repository.CategoryRepository;
import com.teagather.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 섹터 1 : 일자별 카운터 주문번호 + 원자적 회원 집계가 동시 주문에서 겹치거나 사라지지 않는지
//  - 트랜잭션 없는 테스트 (스레드마다 실제 커밋)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seq;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.seed.enabled=false",
        "app.analysis.run-on-startup=false",
        "app.analysis.cron=-"
})
class OrderDaySeqConcurrencyTest {

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired OrderDaySeqRepository daySeqRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;

    Product product;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        daySeqRepository.deleteAll();       // 테스트끼리 카운터를 나눠 쓰지 않게
        Category c = categoryRepository.findAll().stream().findFirst()
                .orElseGet(() -> categoryRepository.save(Category.builder().name("커피").build()));
        product = productRepository.findAll().stream().findFirst()
                .orElseGet(() -> productRepository.save(Product.builder().name("아메리카노").category(c)
                        .price(4000).cost(1000).status(ProductStatus.ON_SALE).build()));
    }

    private Member newMember(String name) {
        return memberRepository.save(Member.builder().username(name + "-" + UUID.randomUUID().toString().substring(0, 8))
                .role(MemberRole.CUSTOMER).created(LocalDateTime.now()).build());
    }

    private Cart cartWithOne() {
        Cart cart = new Cart();
        cart.add(orderService.makeLine(product.getId(), List.of(), 1));
        return cart;
    }

    private List<Throwable> runConcurrently(int n, java.util.function.IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    task.accept(idx);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        return errors;
    }

    @Test
    void 동시_주문_50건은_번호가_겹치지_않고_기존_번호_다음부터_이어진다() throws Exception {
        String prefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        // 카운터 줄이 생기기 전에 이미 있던 그날 주문 (예시 데이터·이전 방식)
        Member old = newMember("old");
        CustomerOrder existing = CustomerOrder.builder().member(old).orderNo(prefix + "-0007")
                .orderedAt(LocalDateTime.now()).build();
        orderRepository.save(existing);

        int n = 50;
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < n; i++) members.add(newMember("m" + i));

        List<Throwable> errors = runConcurrently(n, i ->
                orderService.place(members.get(i), cartWithOne(), PickupType.TAKEOUT, null));

        assertThat(errors).isEmpty();
        List<String> nos = orderRepository.findAll().stream().map(CustomerOrder::getOrderNo).toList();
        assertThat(nos).hasSize(n + 1).doesNotHaveDuplicates();
        Set<String> expected = new HashSet<>();
        for (int k = 7; k <= 7 + n; k++) expected.add(prefix + "-" + String.format("%04d", k));
        assertThat(new HashSet<>(nos)).isEqualTo(expected);   // 0007 ~ 0057, 빈 번호 없음
    }

    @Test
    void 같은_회원의_동시_주문_취소에도_집계가_사라지지_않는다() throws Exception {
        Member m = newMember("same");
        int n = 30;
        List<Throwable> errors = runConcurrently(n, i ->
                orderService.place(m, cartWithOne(), PickupType.TAKEOUT, null));
        assertThat(errors).isEmpty();

        Member after = memberRepository.findById(m.getId()).orElseThrow();
        assertThat(after.getOrderCount()).isEqualTo(n);
        assertThat(after.getTotalSpent()).isEqualTo(4000L * n);
        assertThat(after.getPoint()).isEqualTo(40 * n);
        assertThat(after.getLastOrderAt()).isNotNull();

        // 절반을 동시에 취소
        List<Long> ids = orderRepository.findAll().stream().map(CustomerOrder::getId).limit(n / 2).toList();
        AtomicInteger canceled = new AtomicInteger();
        errors = runConcurrently(ids.size(), i -> {
            orderService.cancelByAdmin(ids.get(i));
            canceled.incrementAndGet();
        });
        assertThat(errors).isEmpty();

        after = memberRepository.findById(m.getId()).orElseThrow();
        int left = n - canceled.get();
        assertThat(after.getOrderCount()).isEqualTo(left);
        assertThat(after.getTotalSpent()).isEqualTo(4000L * left);
        assertThat(after.getPoint()).isEqualTo(40 * left);
    }
}
