package com.teagather.common.advice;

import com.teagather.analysis.entity.AnalysisRun;
import com.teagather.analysis.service.AnalysisService;
import com.teagather.order.constant.OrderStatus;
import com.teagather.order.repository.OrderRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

// 관리자 사이드바 배지 · 분석 상태 카드 (관리자 화면에서만 채운다)
@ControllerAdvice
@RequiredArgsConstructor
public class AdminLayoutAdvice {

    private final OrderRepository orderRepository;
    private final AnalysisService analysisService;

    @ModelAttribute
    public void adminNav(HttpServletRequest request, Model model) {
        if (!request.getRequestURI().startsWith(request.getContextPath() + "/admin")) return;
        if (request.getRequestURI().startsWith(request.getContextPath() + "/admin/login")) return;
        model.addAttribute("navOpenOrders", orderRepository.countByStatusIn(List.of(OrderStatus.PLACED, OrderStatus.PREPARING)));
        AnalysisRun run = analysisService.latestSuccess().orElse(null);
        model.addAttribute("navAnalysisRunning", analysisService.isRunning());
        model.addAttribute("navAnalysisEngine", run == null ? null : run.getEngine());
        model.addAttribute("navAnalysisAt", run == null ? null : run.getFinishedAt());
    }
}
