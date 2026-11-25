package com.teagather.common.exception;

// 주문번호·잠금 경합으로 재시도까지 다 실패했을 때 (장바구니는 그대로 남아 있다)
public class OrderBusyException extends BusinessException {

    public static final String MESSAGE = "주문이 몰려 접수하지 못했어요. 장바구니는 그대로예요. 다시 시도해 주세요.";

    public OrderBusyException(Throwable cause) {
        super(MESSAGE);
        initCause(cause);
    }
}
