package com.teagather.order.verify;

import com.teagather.member.entity.Member;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// 한 고객이 여러 기기에서 동시에 주문·취소 : 회원 집계(주문 수·누적 금액·포인트)가
// 실제 주문(취소 제외)에서 다시 계산한 값과 같아야 한다 (lost update 금지)
//  실제 요청 경로(MockMvc, open-in-view 포함)로 보낸다 — 서비스만 부르면 운영과 다른 동작을 본다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberAggregateConcurrencyTest extends OrderTestSupport {

    static final int PRE_ORDERS = 10;     // 미리 넣어 두고 동시에 취소할 주문
    static final int NEW_ORDERS = 10;     // 동시에 새로 넣을 주문

    @Autowired MockMvc mvc;

    @RepeatedTest(5)
    @DisplayName("같은 고객 동시 주문 10 + 취소 10 - 회원 집계 = 실제 주문 합계")
    void aggregatesMatchOrders() throws Exception {
        Member m = newCustomer("agg-user");
        List<Long> toCancel = new ArrayList<>();
        for (int i = 0; i < PRE_ORDERS; i++) {
            Long id = placeViaWeb(m, new MockHttpSession());
            assertThat(id).as("미리 넣는 주문 %d", i).isNotNull();
            toCancel.add(id);
        }

        ExecutorService pool = Executors.newFixedThreadPool(PRE_ORDERS + NEW_ORDERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(PRE_ORDERS + NEW_ORDERS);
        AtomicInteger placeFailed = new AtomicInteger();
        AtomicInteger cancelFailed = new AtomicInteger();
        // 각 요청은 따로 준비한 세션(기기)에서 보낸다
        List<MockHttpSession> sessions = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < NEW_ORDERS; i++) {
            MockHttpSession s = new MockHttpSession();
            addToCart(m, s);
            sessions.add(s);
            tokens.add(checkoutToken(m, s));
        }
        try {
            for (Long id : toCancel) {
                pool.submit(() -> {
                    try {
                        start.await();
                        int st = mvc.perform(post("/orders/" + id + "/cancel").with(user(m.getUsername())).with(csrf()))
                                .andReturn().getResponse().getStatus();
                        if (st >= 400) cancelFailed.incrementAndGet();
                    } catch (Exception e) {
                        cancelFailed.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            for (int i = 0; i < NEW_ORDERS; i++) {
                MockHttpSession s = sessions.get(i);
                String token = tokens.get(i);
                pool.submit(() -> {
                    try {
                        start.await();
                        String to = submit(m, s, token).getResponse().getRedirectedUrl();
                        if (to == null || !to.startsWith("/orders/")) placeFailed.incrementAndGet();
                    } catch (Exception e) {
                        placeFailed.incrementAndGet();
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

        Member after = reload(m);
        Expected exp = recompute(m);
        System.out.printf("[BASELINE] MemberAggregate placeFailed=%d cancelFailed=%d "
                        + "orderCount %d(expected %d) totalSpent %d(expected %d) point %d(expected %d)%n",
                placeFailed.get(), cancelFailed.get(), after.getOrderCount(), exp.orderCount(),
                after.getTotalSpent(), exp.totalSpent(), after.getPoint(), exp.point());

        assertThat(after.getOrderCount()).as("주문 수 = 취소 안 된 주문 수").isEqualTo(exp.orderCount());
        assertThat(after.getTotalSpent()).as("누적 금액 = 취소 안 된 결제 합계").isEqualTo(exp.totalSpent());
        assertThat(after.getPoint()).as("포인트 = 취소 안 된 적립 합계").isEqualTo(exp.point());
    }

    private Long placeViaWeb(Member m, MockHttpSession s) throws Exception {
        addToCart(m, s);
        String to = submit(m, s, checkoutToken(m, s)).getResponse().getRedirectedUrl();
        if (to == null || !to.startsWith("/orders/")) return null;
        return Long.valueOf(to.substring("/orders/".length()).replaceAll("\\?.*", ""));
    }

    private void addToCart(Member m, MockHttpSession s) throws Exception {
        mvc.perform(post("/cart/add").session(s).with(user(m.getUsername())).with(csrf())
                .param("productId", product.getId().toString()).param("quantity", "1"));
    }

    private String checkoutToken(Member m, MockHttpSession s) throws Exception {
        MvcResult r = mvc.perform(get("/order").session(s).with(user(m.getUsername()))).andReturn();
        return String.valueOf(r.getModelAndView().getModel().get("orderToken"));
    }

    private MvcResult submit(Member m, MockHttpSession s, String token) throws Exception {
        return mvc.perform(post("/order").session(s).with(user(m.getUsername())).with(csrf())
                .param("pickupType", "TAKEOUT").param("orderToken", token)).andReturn();
    }
}
