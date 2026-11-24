package com.teagather.common.advice;

import com.teagather.common.exception.BusinessException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

// 전역 예외 처리 : 사용자에게는 안내 화면(message.html)만 보여 준다 (스택 트레이스는 로그에만)
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({BusinessException.class, IllegalArgumentException.class})
    public String business(RuntimeException e, HttpServletRequest req, HttpServletResponse res, Model model) {
        log.info("=======> 업무 오류 : {}", e.getMessage());
        return view(req, res, model, HttpStatus.BAD_REQUEST, e.getMessage());
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
        String rid = UUID.randomUUID().toString().substring(0, 8);
        log.error("=======> 처리하지 못한 오류 [{}] {}", rid, req.getRequestURI(), e);
        return view(req, res, model, HttpStatus.INTERNAL_SERVER_ERROR, "잠시 문제가 생겼어요. 다시 시도해 주세요. (요청 번호 " + rid + ")");
    }

    private String view(HttpServletRequest req, HttpServletResponse res, Model model, HttpStatus status, String message) {
        res.setStatus(status.value());
        model.addAttribute("message", message);
        model.addAttribute("adminArea", req.getRequestURI().startsWith(req.getContextPath() + "/admin"));
        return "message";
    }
}
