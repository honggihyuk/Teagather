package com.teagather.common.advice;

import com.teagather.common.exception.BusinessException;
import com.teagather.common.exception.OrderBusyException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.util.UUID;

// 전역 예외 처리 : 사용자에게는 안내 화면(message.html)만 보여 준다 (스택 트레이스는 로그에만)
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    // 화면에 그대로 보여 줄 수 있는 메시지는 BusinessException 이 담은 것뿐이다
    @ExceptionHandler(BusinessException.class)
    public String business(BusinessException e, HttpServletRequest req, HttpServletResponse res, Model model) {
        log.info("=======> 업무 오류 : {}", e.getMessage());
        return view(req, res, model, HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // 주문 재시도 소진 : 주문서로 돌려보내고 토스트로 알린다 (장바구니는 그대로)
    // 보통은 OrderController 가 먼저 잡는다. 다른 경로에서 올라온 경우의 안전망
    @ExceptionHandler(OrderBusyException.class)
    public String orderBusy(OrderBusyException e, HttpServletRequest req) {
        log.warn("=======> 주문 혼잡 : {}", e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
        RequestContextUtils.getOutputFlashMap(req).put("errorMessage", e.getMessage());
        return "redirect:/order";
    }

    // 라이브러리·JDK 내부 메시지가 섞일 수 있어 원문은 로그에만 남긴다
    @ExceptionHandler(IllegalArgumentException.class)
    public String illegalArgument(IllegalArgumentException e, HttpServletRequest req, HttpServletResponse res, Model model) {
        log.info("=======> 잘못된 요청 값 {} : {}", req.getRequestURI(), e.getMessage());
        return view(req, res, model, HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않아요.");
    }

    // 동시에 같은 데이터를 바꾸다 겹친 경우 (주문번호 중복, 낙관적 잠금 충돌 등) : 제약 이름·SQL 은 보여 주지 않는다
    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    public String conflict(RuntimeException e, HttpServletRequest req, HttpServletResponse res, Model model) {
        String rid = rid();
        log.warn("=======> 데이터 충돌 [{}] {} : {}", rid, req.getRequestURI(), e.getClass().getSimpleName(), e);
        return view(req, res, model, HttpStatus.CONFLICT, "다른 요청과 겹쳐 처리하지 못했어요. 다시 시도해 주세요. (요청 번호 " + rid + ")");
    }

    // 잠금 대기·쿼리 시간 초과 : 잠시 뒤 다시 하면 되는 일시적 오류
    @ExceptionHandler({PessimisticLockingFailureException.class, QueryTimeoutException.class})
    public String busy(RuntimeException e, HttpServletRequest req, HttpServletResponse res, Model model) {
        String rid = rid();
        log.warn("=======> 일시적 혼잡 [{}] {} : {}", rid, req.getRequestURI(), e.getClass().getSimpleName());
        return view(req, res, model, HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 잠시 처리하지 못했어요. 잠시 후 다시 시도해 주세요. (요청 번호 " + rid + ")");
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public String notFound(EntityNotFoundException e, HttpServletRequest req, HttpServletResponse res, Model model) {
        return view(req, res, model, HttpStatus.NOT_FOUND, e.getMessage() == null ? "찾을 수 없어요." : e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public String noResource(HttpServletRequest req, HttpServletResponse res, Model model) {
        return view(req, res, model, HttpStatus.NOT_FOUND, "없는 화면이에요. 주소를 확인해 주세요.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public String badParam(HttpServletRequest req, HttpServletResponse res, Model model) {
        return view(req, res, model, HttpStatus.BAD_REQUEST, "주소의 값이 올바르지 않아요.");
    }

    // 보안 예외는 Spring Security 가 처리하도록 다시 던진다
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void security(RuntimeException e) {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    public String unknown(Exception e, HttpServletRequest req, HttpServletResponse res, Model model) {
        String rid = rid();
        log.error("=======> 처리하지 못한 오류 [{}] {}", rid, req.getRequestURI(), e);
        return view(req, res, model, HttpStatus.INTERNAL_SERVER_ERROR, "잠시 문제가 생겼어요. 다시 시도해 주세요. (요청 번호 " + rid + ")");
    }

    private static String rid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String view(HttpServletRequest req, HttpServletResponse res, Model model, HttpStatus status, String message) {
        res.setStatus(status.value());
        model.addAttribute("message", message);
        model.addAttribute("adminArea", req.getRequestURI().startsWith(req.getContextPath() + "/admin"));
        return "message";
    }
}
