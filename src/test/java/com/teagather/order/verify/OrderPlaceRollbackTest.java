package com.teagather.order.verify;

import com.teagather.member.entity.Member;
import com.teagather.order.cart.Cart;
import com.teagather.order.constant.PickupType;
import com.teagather.order.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

// 서비스 계층 롤백 : 주문 도중 실패하면 DB 에 흔적이 남지 않고, 장바구니도 그대로여야 한다
@SpringBootTest
@ActiveProfiles("test")
class OrderPlaceRollbackTest extends OrderTestSupport {

    @MockitoSpyBean
    OrderRepository orderRepository;

    // 리포지토리 스파이는 인터페이스 프록시라 callRealMethod 를 못 쓴다 → 같은 트랜잭션의 EntityManager 로 직접 INSERT
    @PersistenceContext
    EntityManager em;

    @Test
    @DisplayName("저장 직후 실패 - 주문·주문상품 행이 늘지 않고 회원 집계도 그대로")
    void failureAfterInsertRollsBackEverything() {
        Member m = newCustomer("rb-service");
        Cart cart = cartWithOneLine();
        long orders = orderRepository.count();
        long items = orderItemRepository.count();
        Member before = reload(m);

        // INSERT 는 실제로 나간 다음 실패 (부분 저장 흔적이 남는지 확인)
        doAnswer(inv -> {
            em.persist(inv.getArgument(0));
            em.flush();
            throw new DataIntegrityViolationException("검증용 강제 실패 (저장 직후)");
        }).when(orderRepository).save(any());

        assertThatThrownBy(() -> orderService.place(m, cart, PickupType.TAKEOUT, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        Member after = reload(m);
        System.out.printf("[BASELINE] OrderPlaceRollback orders %d->%d items %d->%d cartLines=%d%n",
                orders, orderRepository.count(), items, orderItemRepository.count(), cart.getLines().size());

        assertThat(orderRepository.count()).as("주문 행 수").isEqualTo(orders);
        assertThat(orderItemRepository.count()).as("주문상품 행 수").isEqualTo(items);
        assertThat(after.getOrderCount()).as("주문 수").isEqualTo(before.getOrderCount());
        assertThat(after.getTotalSpent()).as("누적 금액").isEqualTo(before.getTotalSpent());
        assertThat(after.getPoint()).as("포인트").isEqualTo(before.getPoint());
        assertThat(cart.getLines()).as("장바구니").hasSize(1);
    }

    @Test
    @DisplayName("place 는 끝났지만 커밋이 안 되면 - 주문은 사라지고 장바구니는 남아야 한다")
    void commitFailureKeepsCart() {
        Member m = newCustomer("rb-commit");
        Cart cart = cartWithOneLine();
        long orders = orderRepository.count();

        // 바깥 트랜잭션 안에서 place 를 부르고, place 가 돌아온 뒤 커밋 전에 실패시킨다
        //  = 커밋 시점 실패 (낙관적 락 충돌, 커밋 중 제약 위반 등) 를 흉내 낸다
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            orderService.place(m, cart, PickupType.TAKEOUT, null);
            throw new IllegalStateException("검증용 강제 실패 (커밋 직전)");
        })).isInstanceOf(IllegalStateException.class);

        System.out.printf("[BASELINE] CommitFailureKeepsCart orders %d->%d cartLines=%d%n",
                orders, orderRepository.count(), cart.getLines().size());

        assertThat(orderRepository.count()).as("주문 행 수").isEqualTo(orders);
        // 계약 : 장바구니 비우기는 OrderService 가 아니라 주문이 '커밋된 뒤' 호출한 쪽이 한다
        assertThat(cart.getLines()).as("커밋 실패 뒤 장바구니").hasSize(1);
    }
}
