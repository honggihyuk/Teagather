package com.teagather.common.exception;

// 화면에 그대로 보여 줄 수 있는 업무 오류 (예: "장바구니가 비어 있어요")
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
