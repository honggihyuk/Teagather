package com.teagather.order.web;

import com.teagather.common.exception.BusinessException;
import com.teagather.common.exception.OrderBusyException;
import com.teagather.order.cart.Cart;
import com.teagather.order.cart.CartLine;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.service.OrderPlacementFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 주문 실패 시 사용자 관점 검증 : 장바구니 보존 · 토큰 복원 · 안내 메시지 · 정보 노출 없음
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orderweb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "app.seed.enabled=false",
        "app.analysis.run-on-startup=false"
})
@WithMockUser(username = "customer1")
class OrderControllerFailureFlowTest {

    // Boot 4 는 MockMvc 보안 연동이 별도 모듈이라, 여기서 직접 springSecurity() 를 붙인다
    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @MockitoBean
    OrderPlacementFacade facade;

    MockHttpSession sessionWithCart() {
        MockHttpSession session = new MockHttpSession();
        Cart cart = new Cart();
        cart.add(new CartLine(1L, "아메리카노", List.of(), "", 4500, 0, 1, "brown", "cup"));
        session.setAttribute("scopedTarget.cart", cart);
        return session;
    }

    Cart cartOf(MockHttpSession s) {
        return (Cart) s.getAttribute("scopedTarget.cart");
    }

    String openCheckout(MockHttpSession s) throws Exception {
        mvc.perform(get("/order").session(s)).andExpect(status().isOk());
        return cartOf(s).getOrderToken();
    }

    @Test
    void 재시도_소진이면_주문서로_돌아가고_장바구니와_토큰이_남는다() throws Exception {
        MockHttpSession s = sessionWithCart();
        String token = openCheckout(s);
        when(facade.place(any(), any(), any(), any())).thenThrow(new OrderBusyException(new DataIntegrityViolationException("x")));

        mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", token))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/order"))
                .andExpect(flash().attribute("errorMessage", OrderBusyException.MESSAGE));

        assertThat(cartOf(s).getLines()).hasSize(1);
        assertThat(cartOf(s).getOrderToken()).isEqualTo(token);

        // 같은 주문서로 다시 보내면 "이미 처리한 주문서" 가 아니라 다시 시도된다
        CustomerOrder ok = mock(CustomerOrder.class);
        when(ok.getId()).thenReturn(7L);
        when(ok.getOrderNo()).thenReturn("251125-0001");
        doReturn(ok).when(facade).place(any(), any(), any(), any());
        mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", token))
                .andExpect(redirectedUrl("/orders/7?done"));
        assertThat(cartOf(s).getLines()).isEmpty();                  // 성공한 뒤에만 비운다
        assertThat(cartOf(s).getOrderToken()).isNull();
    }

    @Test
    void 업무_오류도_주문서로_돌아가고_토큰이_남는다() throws Exception {
        MockHttpSession s = sessionWithCart();
        String token = openCheckout(s);
        when(facade.place(any(), any(), any(), any())).thenThrow(new BusinessException("지금은 주문할 수 없는 메뉴예요."));

        mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", token))
                .andExpect(redirectedUrl("/order"))
                .andExpect(flash().attribute("errorMessage", "지금은 주문할 수 없는 메뉴예요."));
        assertThat(cartOf(s).getLines()).hasSize(1);
        assertThat(cartOf(s).getOrderToken()).isEqualTo(token);
    }

    @Test
    void 예상_못한_DB_오류도_주문서로_돌아가고_SQL_은_보이지_않는다() throws Exception {
        MockHttpSession s = sessionWithCart();
        String token = openCheckout(s);
        when(facade.place(any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("could not execute statement; constraint [PUBLIC.UK_SECRET]"));

        MvcResult r = mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", token))
                .andExpect(redirectedUrl("/order"))
                .andReturn();
        String msg = (String) r.getFlashMap().get("errorMessage");
        assertThat(msg).doesNotContain("UK_SECRET").doesNotContain("이미 처리한").contains("장바구니는 그대로");
        assertThat(cartOf(s).getLines()).hasSize(1);
        assertThat(cartOf(s).getOrderToken()).isEqualTo(token);
    }

    @Test
    void 성공한_주문서를_다시_보내면_그_주문으로_안내한다() throws Exception {
        MockHttpSession s = sessionWithCart();
        String token = openCheckout(s);
        CustomerOrder ok = mock(CustomerOrder.class);
        when(ok.getId()).thenReturn(9L);
        when(ok.getOrderNo()).thenReturn("251125-0002");
        when(facade.place(any(), any(), any(), any())).thenReturn(ok);

        mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", token))
                .andExpect(redirectedUrl("/orders/9?done"));
        mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", token))
                .andExpect(redirectedUrl("/orders/9"));
        verify(facade, times(1)).place(any(), any(), any(), any());
    }

    @Test
    void 실패_뒤_주문서를_새로_열면_옛_토큰은_새로고침_안내() throws Exception {
        MockHttpSession s = sessionWithCart();
        String oldToken = openCheckout(s);
        when(facade.place(any(), any(), any(), any())).thenThrow(new OrderBusyException(new DataIntegrityViolationException("x")));
        mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", oldToken))
                .andExpect(redirectedUrl("/order"));
        String newToken = openCheckout(s);                      // 리다이렉트된 주문서에서 새 토큰
        assertThat(newToken).isNotEqualTo(oldToken);

        MvcResult r = mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", oldToken))
                .andExpect(redirectedUrl("/order")).andReturn();
        assertThat((String) r.getFlashMap().get("errorMessage")).contains("새로 고쳐졌어요").doesNotContain("이미 처리한");

        CustomerOrder ok = mock(CustomerOrder.class);
        when(ok.getId()).thenReturn(11L);
        doReturn(ok).when(facade).place(any(), any(), any(), any());
        mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", newToken))
                .andExpect(redirectedUrl("/orders/11?done"));
    }

    // 더블클릭 : 첫 요청이 주문을 만드는 동안(facade 안에서 대기) 같은 토큰으로 두 번째 요청이 온다
    //  → 두 번째는 주문을 만들지 않고 "처리 중" 안내와 함께 주문 내역(/orders)으로 가야 한다
    //    (주문서로 보내면 곧 장바구니가 비어 /cart 로 넘어가 아무 안내도 못 본다)
    @Test
    void 더블클릭한_두번째_요청은_처리중_안내와_함께_주문내역으로() throws Exception {
        MockHttpSession s = sessionWithCart();
        String token = openCheckout(s);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CustomerOrder ok = mock(CustomerOrder.class);
        when(ok.getId()).thenReturn(21L);
        when(ok.getOrderNo()).thenReturn("251125-0021");
        when(facade.place(any(), any(), any(), any())).thenAnswer(inv -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).as("테스트가 첫 요청을 풀어 줘야 한다").isTrue();
            return ok;
        });

        // 다른 스레드에서는 @WithMockUser 가 보장되지 않으므로 사용자를 요청에 직접 싣는다
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(post("/order").session(s).with(csrf()).with(user("customer1")).param("orderToken", token)).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(entered.await(10, TimeUnit.SECONDS)).as("첫 요청이 주문 생성 중이어야 한다").isTrue();

        MvcResult second = mvc.perform(post("/order").session(s).with(csrf()).with(user("customer1")).param("orderToken", token))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders"))
                .andReturn();
        assertThat((String) second.getFlashMap().get("shopMessage")).isEqualTo("주문을 처리하고 있어요. 주문 내역에서 확인해 주세요.");
        assertThat(second.getFlashMap().get("errorMessage")).isNull();

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getRedirectedUrl()).isEqualTo("/orders/21?done");
        verify(facade, times(1)).place(any(), any(), any(), any());
        assertThat(cartOf(s).isInFlight(token)).as("끝나면 처리 중 표시는 지워진다").isFalse();

        // 끝난 뒤 같은 토큰을 또 보내면 기존 규칙대로 그 주문으로 안내
        mvc.perform(post("/order").session(s).with(csrf()).with(user("customer1")).param("orderToken", token))
                .andExpect(redirectedUrl("/orders/21"));
    }

    // 처리 중이던 첫 요청이 실패하면 처리 중 표시가 풀리고 토큰이 돌아온다 → 같은 주문서로 다시 주문 가능
    @Test
    void 처리중이던_요청이_실패하면_처리중_표시가_풀리고_다시_주문할_수_있다() throws Exception {
        MockHttpSession s = sessionWithCart();
        String token = openCheckout(s);
        when(facade.place(any(), any(), any(), any())).thenThrow(new OrderBusyException(new DataIntegrityViolationException("x")));
        mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", token))
                .andExpect(redirectedUrl("/order"));
        assertThat(cartOf(s).isInFlight(token)).isFalse();
        assertThat(cartOf(s).getOrderToken()).isEqualTo(token);

        CustomerOrder ok = mock(CustomerOrder.class);
        when(ok.getId()).thenReturn(22L);
        doReturn(ok).when(facade).place(any(), any(), any(), any());
        mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", token))
                .andExpect(redirectedUrl("/orders/22?done"));
    }

    @Test
    void 토큰이_틀리면_주문하지_않고_주문서로() throws Exception {
        MockHttpSession s = sessionWithCart();
        openCheckout(s);
        mvc.perform(post("/order").session(s).with(csrf()).param("orderToken", "wrong"))
                .andExpect(redirectedUrl("/order"))
                .andExpect(flash().attributeExists("errorMessage"));
        verify(facade, never()).place(any(), any(), any(), any());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void actuator_metrics_는_ADMIN_만() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
    }

    @Test
    void actuator_metrics_는_고객이_볼_수_없다() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
