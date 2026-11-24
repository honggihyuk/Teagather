package com.teagather.member.controller;

import com.teagather.member.dto.SignupForm;
import com.teagather.member.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

// 고객 로그인 · 회원가입 (POST /member/login 은 Spring Security 가 처리)
@Controller
@RequiredArgsConstructor
@RequestMapping("/member")
public class MemberController {

    private final MemberService memberService;

    @GetMapping("/login")
    public String login(Authentication auth) {
        if (loggedIn(auth)) return "redirect:/";
        return "member/login";
    }

    @GetMapping("/signup")
    public String signupForm(@ModelAttribute("signupForm") SignupForm form, Authentication auth) {
        if (loggedIn(auth)) return "redirect:/";
        return "member/signup";
    }

    @PostMapping("/signup")
    public String signup(@Valid @ModelAttribute("signupForm") SignupForm form, BindingResult br) {
        if (br.hasErrors()) return "member/signup";
        if (memberService.signup(form, br) == null) return "member/signup";
        return "redirect:/member/login?signup";
    }

    private static boolean loggedIn(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal());
    }
}
