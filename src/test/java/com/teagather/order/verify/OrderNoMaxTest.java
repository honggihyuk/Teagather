package com.teagather.order.verify;

import com.teagather.member.entity.Member;
import com.teagather.order.constant.PickupType;
import com.teagather.order.entity.CustomerOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 하루 주문이 9999 건을 넘어 번호가 다섯 자리가 되어도 "그날 마지막 번호" 를 바르게 찾는다
//  (카운터 줄이 없을 때 이 값으로 시작 번호를 잡으므로, 틀리면 이미 쓴 번호를 다시 낸다)
@SpringBootTest
@ActiveProfiles("test")
class OrderNoMaxTest extends OrderTestSupport {

    static final String PREFIX = "991231";      // 오늘과 겹치지 않는 날짜

    @Test
    @DisplayName("9999 와 10000 이 함께 있으면 10000 이 마지막 번호")
    void fiveDigitSequenceIsMax() {
        Member m = newCustomer("max-no");
        renumber(orderService.place(m, cartWithOneLine(), PickupType.TAKEOUT, null), PREFIX + "-9999");
        renumber(orderService.place(m, cartWithOneLine(), PickupType.TAKEOUT, null), PREFIX + "-10000");
        renumber(orderService.place(m, cartWithOneLine(), PickupType.TAKEOUT, null), PREFIX + "-0042");

        assertThat(orderRepository.findMaxOrderNo(PREFIX)).isEqualTo(PREFIX + "-10000");
    }

    @Test
    @DisplayName("그날 주문이 없으면 null")
    void noOrdersIsNull() {
        assertThat(orderRepository.findMaxOrderNo(PREFIX)).isNull();
    }

    private void renumber(CustomerOrder o, String orderNo) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            CustomerOrder x = orderRepository.findById(o.getId()).orElseThrow();
            x.setOrderNo(orderNo);
        });
    }
}
