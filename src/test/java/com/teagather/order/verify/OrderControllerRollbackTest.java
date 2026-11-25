package com.teagather.order.verify;

import com.teagather.member.entity.Member;
import com.teagather.order.cart.Cart;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.repository.OrderDaySeqRepository;
import com.teagather.order.repository.OrderRepository;
import com.teagather.order.service.SafePlaceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// 사용자 관점 롤백 : 주문 저장이 실패해도
//  ① 500 화면이 아니라 주문서(/order)로 돌아가 안내를 받고
//  ② 장바구니가 남아 있고
//  ③ "이미 처리한 주문서" 같은 거짓 안내가 없고
//  ④ DB 에 주문·회원 집계 흔적이 없고
//  ⑤ 다시 주문하면 정상 처리된다
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderControllerRollbackTest extends OrderTestSupport {

    static final String ALREADY = "이미 처리한";

    @Autowired MockMvc mvc;

    @Autowired SafePlaceProperties safePlaceProperties;

    @MockitoSpyBean
    OrderRepository orderRepository;

    @MockitoSpyBean
    OrderDaySeqRepository orderDaySeqRepository;

    // ===== 주입 경로 B (구현과 무관) : 주문 저장이 항상 실패 =====
    @Test
    @DisplayName("저장 실패 - 주문서로 돌아가고 장바구니·DB·회원 집계가 그대로, 다시 주문하면 성공")
    void saveFailureIsSafe() throws Exception {
        Member m = newCustomer("rb-web-b");
        MockHttpSession session = sessionWithCartLine(m);
        String token = checkoutToken(m, session);
        Snapshot before = snapshot(m);

        doThrow(new DataIntegrityViolationException("검증용 강제 실패 (주문 저장)")).when(orderRepository).save(any());
        doThrow(new DataIntegrityViolationException("검증용 강제 실패 (주문 저장)")).when(orderRepository).saveAndFlush(any());

        MvcResult r = submit(m, session, token);
        recordBaseline("B 저장실패", r, session, before, m);
        assertSafeFailure(r, session, before, m);

        // ⑤ 장애가 사라진 뒤 새 주문서로 다시 주문하면 성공
        reset(orderRepository);
        MvcResult retry = submit(m, session, checkoutToken(m, session));
        assertThat(retry.getResponse().getRedirectedUrl()).as("재주문 결과").startsWith("/orders/");
        assertThat(orderRepository.count()).as("재주문 후 주문 수").isEqualTo(before.orders + 1);
    }

    @Test
    @DisplayName("저장 실패 뒤 같은 주문서를 다시 내도 '이미 처리한 주문서' 거짓 안내가 없어야 한다")
    void resubmitAfterFailureIsNotReportedAsDone() throws Exception {
        Member m = newCustomer("rb-web-resubmit");
        MockHttpSession session = sessionWithCartLine(m);
        String token = checkoutToken(m, session);
        long orders = orderRepository.count();

        doThrow(new DataIntegrityViolationException("검증용 강제 실패 (주문 저장)")).when(orderRepository).save(any());
        doThrow(new DataIntegrityViolationException("검증용 강제 실패 (주문 저장)")).when(orderRepository).saveAndFlush(any());
        submit(m, session, token);

        // 뒤로 가기 → 같은 주문서 다시 제출
        MvcResult again = submit(m, session, token);
        Object msg = again.getFlashMap().get("errorMessage");
        System.out.printf("[BASELINE] resubmit status=%d redirect=%s errorMessage=%s orders=%d->%d%n",
                again.getResponse().getStatus(), again.getResponse().getRedirectedUrl(), msg, orders, orderRepository.count());

        assertThat(orderRepository.count()).as("주문 수 (실패했으므로 그대로)").isEqualTo(orders);
        assertThat(String.valueOf(msg)).as("주문이 없는데 '이미 처리했다'고 안내하면 안 됨").doesNotContain(ALREADY);
        assertThat(again.getResponse().getRedirectedUrl()).as("주문이 된 것처럼 주문 목록으로 보내면 안 됨").isNotEqualTo("/orders");
    }

    // ===== 주입 경로 A : 오늘 이미 있는 주문번호와 충돌 =====
    //  일자별 카운터가 이미 쓴 번호를 다시 내주도록 만든다 → 매 시도마다 order_no unique 위반
    //  → 재시도를 모두 써도 충돌하므로 결과는 반드시 "안전한 실패" 여야 한다
    //    (성공 분기를 두면 스텁이 빠지는 회귀가 생겨도 아무것도 검증하지 않고 통과해 버린다)
    //  (수정 전 기준선은 count/exists 를 속이는 방식으로 쟀다 : docs/verification/baseline-before.md)
    @Test
    @DisplayName("주문번호 충돌이 재시도 내내 계속되면 - 설정된 횟수만큼 시도한 뒤 안전하게 실패한다 (500·장바구니 소실·거짓 안내 금지)")
    void orderNoConflictExhaustsRetriesAndFailsSafely() throws Exception {
        Member other = newCustomer("rb-web-first");
        CustomerOrder first = orderService.place(other, cartWithOneLine(), PickupType.TAKEOUT, null);   // 오늘 번호 하나 차지
        int taken = Integer.parseInt(first.getOrderNo().substring(first.getOrderNo().lastIndexOf('-') + 1));

        Member m = newCustomer("rb-web-a");
        MockHttpSession session = sessionWithCartLine(m);
        String token = checkoutToken(m, session);
        Snapshot before = snapshot(m);

        // 카운터가 이미 쓴 번호를 다시 내주게 만든다 (앞선 준비 단계의 호출 기록은 지운다)
        clearInvocations(orderDaySeqRepository);
        doReturn(taken).when(orderDaySeqRepository).currentNo(any());

        MvcResult r = submit(m, session, token);
        recordBaseline("A 번호충돌", r, session, before, m);

        assertSafeFailure(r, session, before, m);
        int attempts = 1 + safePlaceProperties.effectiveRetries();      // 설정값에서 읽는다 (하드코딩 금지)
        verify(orderDaySeqRepository, times(attempts).description("첫 시도 + 재시도 " + (attempts - 1) + "번"))
                .currentNo(any());
    }

    // ===== 안전한 실패의 정의 =====
    private void assertSafeFailure(MvcResult r, MockHttpSession session, Snapshot before, Member m) {
        assertThat(r.getResponse().getStatus()).as("① 500 오류 화면이 아니라 리다이렉트").isBetween(300, 399);
        assertThat(r.getResponse().getRedirectedUrl()).as("① 주문서로 돌아감").startsWith("/order").doesNotStartWith("/orders");
        Object msg = r.getFlashMap().get("errorMessage");
        assertThat(msg).as("① 안내 메시지").isNotNull();
        assertThat(String.valueOf(msg)).as("③ 거짓 안내 금지").doesNotContain(ALREADY);
        assertThat(cart(session).getLines()).as("② 장바구니 보존").hasSize(1);
        assertThat(orderRepository.count()).as("④ 주문 행").isEqualTo(before.orders);
        assertThat(orderItemRepository.count()).as("④ 주문상품 행").isEqualTo(before.items);
        Member after = reload(m);
        assertThat(after.getOrderCount()).as("④ 주문 수").isEqualTo(before.orderCount);
        assertThat(after.getTotalSpent()).as("④ 누적 금액").isEqualTo(before.totalSpent);
        assertThat(after.getPoint()).as("④ 포인트").isEqualTo(before.point);
    }

    // ===== 도우미 =====

    private record Snapshot(long orders, long items, int orderCount, long totalSpent, int point) {}

    private Snapshot snapshot(Member m) {
        Member x = reload(m);
        return new Snapshot(orderRepository.count(), orderItemRepository.count(), x.getOrderCount(), x.getTotalSpent(), x.getPoint());
    }

    private void recordBaseline(String label, MvcResult r, MockHttpSession session, Snapshot before, Member m) {
        Member after = reload(m);
        System.out.printf("[BASELINE] %s status=%d redirect=%s errorMessage=%s cartLines=%d orders %d->%d point %d->%d%n",
                label, r.getResponse().getStatus(), r.getResponse().getRedirectedUrl(), r.getFlashMap().get("errorMessage"),
                cart(session).getLines().size(), before.orders, orderRepository.count(), before.point, after.getPoint());
    }

    private MockHttpSession sessionWithCartLine(Member m) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/cart/add").session(session).with(user(m.getUsername())).with(csrf())
                .param("productId", product.getId().toString()).param("quantity", "1"));
        assertThat(cart(session).getLines()).as("장바구니 담기").hasSize(1);
        return session;
    }

    private String checkoutToken(Member m, MockHttpSession session) throws Exception {
        MvcResult r = mvc.perform(get("/order").session(session).with(user(m.getUsername()))).andReturn();
        Object token = r.getModelAndView() == null ? null : r.getModelAndView().getModel().get("orderToken");
        assertThat(token).as("주문서 토큰").isNotNull();
        return token.toString();
    }

    private MvcResult submit(Member m, MockHttpSession session, String token) throws Exception {
        return mvc.perform(post("/order").session(session).with(user(m.getUsername())).with(csrf())
                .param("pickupType", "TAKEOUT").param("orderToken", token)).andReturn();
    }

    static Cart cart(MockHttpSession session) {
        return (Cart) session.getAttribute("scopedTarget.cart");
    }
}
