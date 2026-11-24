package com.teagather.config;

import org.springframework.security.core.AuthenticationException;

// 비밀번호는 맞지만 다른 로그인 화면용 계정일 때 (관리자 ↔ 고객)
public class WrongLoginPageException extends AuthenticationException {
    public WrongLoginPageException(String msg) {
        super(msg);
    }
}
