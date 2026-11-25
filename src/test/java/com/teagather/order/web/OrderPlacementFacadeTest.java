package com.teagather.order.web;

import com.teagather.common.exception.BusinessException;
import com.teagather.common.exception.OrderBusyException;
import com.teagather.member.entity.Member;
import com.teagather.member.service.MemberService;
import com.teagather.order.cart.Cart;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import com.teagather.order.service.OrderPlacementFacade;
import com.teagather.order.service.OrderService;
import com.teagather.order.service.SafePlaceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// 재시도 Facade 단위 검증 : 무엇을 몇 번 다시 하는지, 지표가 맞게 쌓이는지
class OrderPlacementFacadeTest {

    OrderService orderService = mock(OrderService.class);
    MemberService memberService = mock(MemberService.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Cart cart = new Cart();

    @BeforeEach
    void setUp() {
        when(memberService.getByUsername("u")).thenReturn(mock(Member.class));
    }

    OrderPlacementFacade facade(boolean enabled, int retries) {
        return new OrderPlacementFacade(orderService, memberService, registry, new SafePlaceProperties(enabled, retries));
    }

    static DataIntegrityViolationException h2OrderNoDup() {
        SQLException sql = new SQLException(
                "Unique index or primary key violation: \"PUBLIC.UK_ORDERS_INDEX_8 ON PUBLIC.ORDERS(ORDER_NO NULLS FIRST) VALUES ( /* 3 */ '251125-0001' )\"",
                "23505", 23505);
        return new DataIntegrityViolationException("could not execute statement", sql);
    }

    static DataIntegrityViolationException mysqlOrderNoDup() {
        SQLException sql = new SQLException("Duplicate entry '251125-0001' for key 'orders.UKabc123'", "23000", 1062);
        return new DataIntegrityViolationException("could not execute statement", sql);
    }

    static DataIntegrityViolationException notNullViolation() {
        SQLException sql = new SQLException("NULL not allowed for column \"PICKUP_TYPE\"", "23502", 23502);
        return new DataIntegrityViolationException("could not execute statement", sql);
    }

    double count(String name) {
        var c = registry.find(name).counters();
        return c.stream().mapToDouble(x -> x.count()).sum();
    }

    @Test
    void 주문번호_충돌은_새로_시도해서_성공한다_H2() {
        CustomerOrder ok = mock(CustomerOrder.class);
        when(orderService.place(any(), any(), any(), any())).thenThrow(h2OrderNoDup()).thenReturn(ok);

        assertThat(facade(true, 2).place("u", cart, PickupType.TAKEOUT, null)).isSameAs(ok);
        verify(orderService, times(2)).place(any(), any(), any(), any());
        verify(memberService, times(2)).getByUsername("u");       // 시도마다 회원을 다시 읽는다
        assertThat(count("order.place.attempt")).isEqualTo(2);
        assertThat(count("order.place.retry")).isEqualTo(1);
        assertThat(registry.counter("order.place.conflict", "cause", "order_no").count()).isEqualTo(1);
        assertThat(registry.timer("order.place", "outcome", "success").count()).isEqualTo(1);
    }

    @Test
    void 주문번호_충돌_MySQL_메시지도_알아본다() {
        when(orderService.place(any(), any(), any(), any())).thenThrow(mysqlOrderNoDup()).thenReturn(mock(CustomerOrder.class));
        facade(true, 2).place("u", cart, PickupType.TAKEOUT, null);
        verify(orderService, times(2)).place(any(), any(), any(), any());
    }

    @Test
    void 재시도를_다_쓰면_OrderBusyException() {
        when(orderService.place(any(), any(), any(), any())).thenThrow(h2OrderNoDup());

        assertThatThrownBy(() -> facade(true, 2).place("u", cart, PickupType.TAKEOUT, null))
                .isInstanceOf(OrderBusyException.class)
                .hasMessage(OrderBusyException.MESSAGE)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
        verify(orderService, times(3)).place(any(), any(), any(), any());   // 처음 1 + 재시도 2
        assertThat(registry.counter("order.place.failed", "reason", "DataIntegrityViolationException").count()).isEqualTo(1);
        assertThat(registry.timer("order.place", "outcome", "failed").count()).isEqualTo(1);
    }

    @Test
    void 잠금_경합도_재시도한다() {
        when(orderService.place(any(), any(), any(), any()))
                .thenThrow(new CannotAcquireLockException("lock wait timeout")).thenReturn(mock(CustomerOrder.class));
        facade(true, 2).place("u", cart, PickupType.TAKEOUT, null);
        assertThat(registry.counter("order.place.conflict", "cause", "lock").count()).isEqualTo(1);
    }

    @Test
    void 업무_오류는_재시도하지_않고_그대로_던진다() {
        when(orderService.place(any(), any(), any(), any())).thenThrow(new BusinessException("장바구니가 비어 있어요."));
        assertThatThrownBy(() -> facade(true, 2).place("u", cart, PickupType.TAKEOUT, null))
                .isExactlyInstanceOf(BusinessException.class).hasMessage("장바구니가 비어 있어요.");
        verify(orderService, times(1)).place(any(), any(), any(), any());
    }

    @Test
    void 다른_제약_위반은_재시도하지_않는다() {
        when(orderService.place(any(), any(), any(), any())).thenThrow(notNullViolation());
        assertThatThrownBy(() -> facade(true, 2).place("u", cart, PickupType.TAKEOUT, null))
                .isExactlyInstanceOf(DataIntegrityViolationException.class);
        verify(orderService, times(1)).place(any(), any(), any(), any());
    }

    @Test
    void 스위치를_끄면_한_번만_시도하고_안내_메시지로_바꾼다() {
        when(orderService.place(any(), any(), any(), any())).thenThrow(h2OrderNoDup());
        assertThatThrownBy(() -> facade(false, 2).place("u", cart, PickupType.TAKEOUT, null))
                .isInstanceOf(OrderBusyException.class);
        verify(orderService, times(1)).place(any(), any(), any(), any());
    }
}
