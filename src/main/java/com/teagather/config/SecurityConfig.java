package com.teagather.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;

// 로그인을 두 갈래로 나눈다 (InhaWorks 와 같은 방식)
//  - 관리자 : /admin/**  → /admin/login, ADMIN 만
//  - 고객   : 나머지     → /member/login. 메뉴 구경은 로그인 없이, 장바구니부터 로그인
// 체인마다 로그인할 수 있는 계정 종류를 나눈다 (관리자 계정은 고객 로그인 불가, 반대도)
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // ===== 1. 관리자 =====
    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http, UserDetailsService uds, PasswordEncoder encoder) throws Exception {
        http.securityMatcher("/admin/**");
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/admin/login").permitAll()
                .anyRequest().hasRole("ADMIN"));
        http.authenticationManager(loginManager(uds, encoder, true));
        http.formLogin(form -> form
                .loginPage("/admin/login")
                .loginProcessingUrl("/admin/login")
                .defaultSuccessUrl("/admin", true)
                .failureHandler(failure("/admin/login"))
                .permitAll());
        http.logout(logout -> logout
                .logoutUrl("/admin/logout")
                .logoutSuccessUrl("/admin/login?logout")
                .invalidateHttpSession(true));
        http.exceptionHandling(ex -> ex.accessDeniedPage("/admin/login?denied"));
        return http.build();
    }

    // ===== 2. 고객 =====
    @Bean
    @Order(2)
    public SecurityFilterChain shopFilterChain(HttpSecurity http, UserDetailsService uds, PasswordEncoder encoder) throws Exception {
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico", "/error").permitAll()
                .requestMatchers("/", "/menu", "/menu/**", "/member/**").permitAll()
                .anyRequest().authenticated());
        http.authenticationManager(loginManager(uds, encoder, false));
        http.formLogin(form -> form
                .loginPage("/member/login")
                .successHandler(nextOrSaved())
                .failureHandler(failure("/member/login"))
                .permitAll());
        http.logout(logout -> logout
                .logoutUrl("/member/logout")
                .logoutSuccessUrl("/?logout")
                .invalidateHttpSession(true));
        return http.build();
    }

    // 로그인 폼의 next(같은 사이트 주소만) → 없으면 원래 가려던 화면 → 없으면 /
    private static AuthenticationSuccessHandler nextOrSaved() {
        SavedRequestAwareAuthenticationSuccessHandler saved = new SavedRequestAwareAuthenticationSuccessHandler();
        saved.setDefaultTargetUrl("/");
        return (req, res, auth) -> {
            String next = req.getParameter("next");
            if (next != null && next.startsWith("/") && !next.startsWith("//") && !next.contains("\\")) {
                res.sendRedirect(req.getContextPath() + next);
            } else {
                saved.onAuthenticationSuccess(req, res, auth);
            }
        };
    }

    // 비밀번호가 맞은 뒤 계정 종류를 확인한다 : adminOnly = true → ADMIN 만 / false → ADMIN 이 아닌 계정만
    static AuthenticationManager loginManager(UserDetailsService uds, PasswordEncoder encoder, boolean adminOnly) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(encoder);
        provider.setPostAuthenticationChecks(user -> {
            boolean admin = user.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
            if (admin != adminOnly) {
                throw new WrongLoginPageException(admin ? "관리자 계정은 관리자 로그인에서 로그인해 주세요."
                        : "고객 계정은 고객 로그인에서 로그인해 주세요.");
            }
        });
        return new ProviderManager(provider);
    }

    private static AuthenticationFailureHandler failure(String loginPage) {
        return (request, response, ex) -> response.sendRedirect(request.getContextPath() + loginPage
                + (ex instanceof WrongLoginPageException ? "?wrongPage" : "?error"));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
