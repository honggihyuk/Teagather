package com.teagather.order.verify;

import com.teagather.member.entity.Member;
import com.teagather.order.cart.Cart;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// 동시 주문 N건 : 주문번호가 겹치지 않고, 실패 없이, 모두 저장돼야 한다
//  수정 전 코드는 count + exists 로 번호를 만들어 같은 번호가 나온다 → 이 테스트가 실패해야 정상 (red)
@SpringBootTest
@ActiveProfiles("test")
class OrderNoConcurrencyTest extends OrderTestSupport {

    static final int N = 50;
    static final int THREADS = 16;

    @RepeatedTest(5)
    @DisplayName("동시 주문 50건 - 실패 0, 주문번호 중복 0, 저장 50건")
    void concurrentOrdersGetUniqueNumbers() throws Exception {
        List<Member> members = new ArrayList<>();
        List<Cart> carts = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            members.add(newCustomer("conc" + i));
            carts.add(cartWithOneLine());
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(N);
        AtomicInteger failures = new AtomicInteger();
        Map<String, AtomicInteger> failureTypes = new ConcurrentHashMap<>();
        try {
            for (int i = 0; i < N; i++) {
                Member m = members.get(i);
                Cart cart = carts.get(i);
                pool.submit(() -> {
                    try {
                        start.await();
                        orderService.place(m, cart, PickupType.TAKEOUT, null);
                    } catch (Exception e) {
                        failures.incrementAndGet();
                        failureTypes.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("60초 안에 끝나야 함").isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<String> numbers = orderRepository.findAll().stream().map(CustomerOrder::getOrderNo).toList();
        long duplicates = numbers.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .values().stream().filter(c -> c > 1).count();
        // 기준선 기록용 (docs/verification/baseline-before.md)
        System.out.printf("[BASELINE] OrderNoConcurrency N=%d failures=%d saved=%d duplicateNumbers=%d types=%s%n",
                N, failures.get(), numbers.size(), duplicates, failureTypes);

        assertThat(failures.get()).as("동시 주문 실패 건수 (유형 %s)", failureTypes).isZero();
        assertThat(numbers).as("주문번호 중복").doesNotHaveDuplicates();
        assertThat(numbers).as("저장된 주문 수").hasSize(N);
    }
}
