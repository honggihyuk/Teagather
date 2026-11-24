package com.teagather.admin.controller;

import com.teagather.analysis.entity.AnalysisRun;
import com.teagather.analysis.entity.SegmentSummary;
import com.teagather.analysis.service.AnalysisLauncher;
import com.teagather.analysis.service.AnalysisService;
import com.teagather.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.List;

// 관리자 : 고객 분석 · 메뉴 판매 분석 (PyTorch)
@Controller
@RequiredArgsConstructor
@RequestMapping("/admin/analysis")
public class AdminAnalysisController {

    private final AnalysisService analysisService;
    private final AnalysisLauncher analysisLauncher;
    private final ProductService productService;

    @GetMapping("/customers")
    public String customers(Model model) {
        common(model);
        AnalysisRun run = (AnalysisRun) model.getAttribute("run");
        List<SegmentSummary> segments = run == null ? List.of() : analysisService.segments(run.getId());
        int total = segments.stream().mapToInt(SegmentSummary::getCustomerCount).sum();
        model.addAttribute("segments", segments);
        model.addAttribute("segmentTotal", Math.max(1, total));
        model.addAttribute("churnTop", analysisService.churnTop(10));
        return "admin/analysis-customers";
    }

    @GetMapping("/menus")
    public String menus(Model model) {
        common(model);
        AnalysisRun run = (AnalysisRun) model.getAttribute("run");
        if (run != null) {
            model.addAttribute("picks", analysisService.picks(run.getId()));
            model.addAttribute("metrics", analysisService.metrics(run.getId()));
        }
        model.addAttribute("history", analysisService.history());
        return "admin/analysis-menus";
    }

    private void common(Model model) {
        model.addAttribute("run", analysisService.latestSuccess().orElse(null));
        model.addAttribute("latest", analysisService.latest().orElse(null));
        model.addAttribute("running", analysisService.isRunning());
    }

    @PostMapping("/run")
    public String run(@RequestParam(value = "back", defaultValue = "menus") String back, Principal principal, RedirectAttributes ra) {
        if (analysisService.isRunning()) {
            ra.addFlashAttribute("errorMessage", "이미 분석하고 있어요. 잠시 뒤 새로고침해 주세요.");
        } else {
            analysisLauncher.runAsync(principal.getName());
            ra.addFlashAttribute("adminMessage", "분석을 시작했어요. 보통 10~30초 걸려요.");
        }
        return "redirect:/admin/analysis/" + ("customers".equals(back) ? "customers" : "menus");
    }

    @PostMapping("/insights/{id}/apply")
    public String apply(@PathVariable("id") Long id, RedirectAttributes ra) {
        ra.addFlashAttribute("adminMessage", analysisService.apply(id));
        return "redirect:/admin/analysis/menus";
    }

    @PostMapping("/today/clear")
    public String clearToday(RedirectAttributes ra) {
        int n = productService.clearTodayMenus();
        ra.addFlashAttribute("adminMessage", "오늘의 메뉴 " + n + "개를 내렸어요.");
        return "redirect:/admin/analysis/menus";
    }
}
