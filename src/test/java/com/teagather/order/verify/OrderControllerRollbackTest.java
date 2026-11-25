package com.teagather.order.verify;

import com.teagather.member.entity.Member;
import com.teagather.order.cart.Cart;
import com.teagather.order.constant.PickupType;
import com.teagather.order.repository.OrderRepository;
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

    @MockitoSpyBean
    OrderRepository orderRepository;

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

    // ===== 주입 경로 A (현재 번호 발급 방식 전용) : 오늘 이미 있는 주문번호와 충돌 =====
    //  번호 발급이 카운터 방식으로 바뀌면 이 주입은 효과가 없어 주문이 성공할 수 있다
    //  → 결과는 "깨끗한 성공" 이거나 "안전한 실패" 둘 중 하나여야 한다
    @Test
    @DisplayName("주문번호 충돌 - 깨끗하게 성공하거나 안전하게 실패해야 한다 (500·장바구니 소실·거짓 안내 금지)")
    void orderNoConflictIsCleanSuccessOrSafeFailure() throws Exception {
        Member other = newCustomer("rb-web-first");
        orderService.place(other, cartWithOneLine(), PickupType.TAKEOUT, null);   // 오늘 0001 번 차지

        Member m = newCustomer("rb-web-a");
        MockHttpSession session = sessionWithCartLine(m);
        String token = checkoutToken(m, session);
        Snapshot before = snapshot(m);

        // 현재 nextOrderNo 가 "오늘 주문 0건, 0001 번은 비어 있음" 으로 착각하게 만든다
        doReturn(0L).when(orderRepository).countByOrderedAtGreaterThanEqualAndOrderedAtLessThan(any(), any());
        doReturn(false).when(orderRepository).existsByOrderNo(anyString());

        MvcResult r = submit(m, session, token);
        recordBaseline("A 번호충돌", r, session, before, m);

        String to = r.getResponse().getRedirectedUrl();
        if (to != null && to.startsWith("/orders/")) {
            // 깨끗한 성공
            assertThat(orderRepository.count()).isEqualTo(before.orders + 1);
            assertThat(orderRepository.findAll()).extracting("orderNo").doesNotHaveDuplicates();
            assertThat(cart(session).getLines()).as("성공했으면 장바구니는 비워짐").isEmpty();
        } else {
            assertSafeFailure(r, session, before, m);
        }
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
