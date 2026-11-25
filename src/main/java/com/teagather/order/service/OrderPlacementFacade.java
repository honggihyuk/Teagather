package com.teagather.order.service;

import com.teagather.common.exception.OrderBusyException;
import com.teagather.member.entity.Member;
import com.teagather.member.service.MemberService;
import com.teagather.order.cart.Cart;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

// 주문 생성의 바깥 껍데기 (트랜잭션 없음)
//  - 시도마다 OrderService.place 를 새로 불러 "새 트랜잭션" 에서 처음부터 다시 한다
//    (같은 트랜잭션 안에서 잡고 다시 하면 rollback-only 라 소용없다)
//  - 재시도 : 주문번호 충돌·잠금 경합만, app.order.safe-place.max-retries 번까지 (Spring Framework 7 core retry)
//  - 지표 : order.place.attempt / retry / conflict{cause} / failed{reason}, 타이머 order.place{outcome}
//  - 로그 : MDC attemptId 로 한 주문의 재시도 로그를 묶는다
@Slf4j
@Service
@EnableConfigurationProperties(SafePlaceProperties.class)
@RequiredArgsConstructor
public class OrderPlacementFacade {

    static final String MDC_KEY = "attemptId";

    private final OrderService orderService;
    private final MemberService memberService;
    private final MeterRegistry registry;
    private final SafePlaceProperties props;

    public CustomerOrder place(String username, Cart cart, PickupType pickupType, String request) {
        MDC.put(MDC_KEY, UUID.randomUUID().toString().substring(0, 8));
        Timer.Sample sample = Timer.start(registry);
        String outcome = "failed";
        AtomicInteger tries = new AtomicInteger();
        try {
            RetryPolicy policy = RetryPolicy.builder()
                    .maxRetries(props.effectiveRetries())
                    .delay(Duration.ofMillis(20))
                    .jitter(Duration.ofMillis(10))
                    .predicate(OrderConflicts::isRetryable)
                    .build();
            CustomerOrder o = new RetryTemplate(policy)
                    .execute(() -> attempt(tries.incrementAndGet(), username, cart, pickupType, request));
            outcome = "success";
            return o;
        } catch (RetryException e) {
            Throwable last = e.getLastException() != null ? e.getLastException() : e.getCause();
            registry.counter("order.place.failed", "reason", last == null ? "Unknown" : last.getClass().getSimpleName()).increment();
            if (OrderConflicts.isRetryable(last)) {
                log.warn("=======> 주문 재시도 소진 ({}회 시도) : {}", tries.get(), last.getClass().getSimpleName());
                throw new OrderBusyException(last);
            }
            if (last instanceof RuntimeException re) throw re;
            if (last instanceof Error err) throw err;
            throw new IllegalStateException(last);
        } finally {
            sample.stop(registry.timer("order.place", "outcome", outcome));
            MDC.remove(MDC_KEY);
        }
    }

    private CustomerOrder attempt(int no, String username, Cart cart, PickupType pickupType, String request) {
        registry.counter("order.place.attempt").increment();
        if (no > 1) {
            registry.counter("order.place.retry").increment();
            log.info("=======> 주문 재시도 {}회째", no - 1);
        }
        try {
            // 앞 시도가 롤백되면 OSIV 영속성 컨텍스트가 비워져 회원이 detached 가 된다 → 시도마다 다시 읽는다
            Member me = memberService.getByUsername(username);
            return orderService.place(me, cart, pickupType, request);
        } catch (RuntimeException e) {
            String cause = OrderConflicts.cause(e);
            if (cause != null) {
                registry.counter("order.place.conflict", "cause", cause).increment();
                log.warn("=======> 주문 충돌 ({}) {}회째 시도", cause, no);
            }
            throw e;
        }
    }
}
