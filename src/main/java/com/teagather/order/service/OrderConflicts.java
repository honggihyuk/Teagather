package com.teagather.order.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

// 주문 생성에서 "다시 시도하면 성공할 수 있는" 실패인지 가른다
//  - 주문번호(order_no) unique 위반 : H2 는 메시지에 ORDER_NO 가 들어 있고,
//    MySQL 은 키 이름이 자동 생성(UK...)이라 Duplicate entry 의 값 모양(yyMMdd-NNNN)으로 판별한다
//  - 잠금 경합 : PessimisticLockingFailureException (CannotAcquireLockException 포함)
//  업무 오류(BusinessException)나 다른 제약 위반(NOT NULL 등)은 재시도하지 않는다 (버그이거나 결정적 실패)
public final class OrderConflicts {

    private static final Pattern ORDER_NO_VALUE = Pattern.compile("'\\d{6}-\\d{4,}'");

    private OrderConflicts() {
    }

    public static boolean isRetryable(Throwable t) {
        return cause(t) != null;
    }

    // 지표 태그용 : order_no / lock / null(재시도 대상 아님)
    public static String cause(Throwable t) {
        if (t == null) return null;
        if (find(t, PessimisticLockingFailureException.class) != null) return "lock";
        if (find(t, DataIntegrityViolationException.class) != null && isOrderNoViolation(t)) return "order_no";
        return null;
    }

    static boolean isOrderNoViolation(Throwable t) {
        for (Throwable c = t; c != null; c = next(c)) {
            String msg = c.getMessage() == null ? "" : c.getMessage();
            String lower = msg.toLowerCase(Locale.ROOT);
            if (c instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null
                    && cve.getConstraintName().toLowerCase(Locale.ROOT).contains("order_no")) {
                return true;
            }
            if (c instanceof SQLException sql) {
                boolean h2Unique = "23505".equals(sql.getSQLState());
                boolean mysqlDup = sql.getErrorCode() == 1062;
                if ((h2Unique || mysqlDup) && (lower.contains("order_no") || ORDER_NO_VALUE.matcher(msg).find())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static <T extends Throwable> T find(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = next(c)) {
            if (type.isInstance(c)) return type.cast(c);
        }
        return null;
    }

    private static Throwable next(Throwable c) {
        return c.getCause() == c ? null : c.getCause();
    }
}
