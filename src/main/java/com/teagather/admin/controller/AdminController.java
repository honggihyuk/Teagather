package com.teagather.admin.controller;

import com.teagather.admin.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

// 관리자 로그인 · 대시보드
@Controller
@RequiredArgsConstructor
public class AdminController {

    private final DashboardService dashboardService;

    @GetMapping("/admin/login")
    public String login(Authentication auth) {
        if (auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            return "redirect:/admin";
        }
        return "admin/login";
    }

    @GetMapping({"/admin", "/admin/"})
    public String dashboard(Model model) {
        model.addAttribute("dash", dashboardService.build());
        return "admin/dashboard";
    }
}
