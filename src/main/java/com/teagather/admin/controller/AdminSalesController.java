package com.teagather.admin.controller;

import com.teagather.admin.dto.SalesReport;
import com.teagather.admin.service.SalesReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

// 관리자 : 매출 리포트
@Controller
@RequiredArgsConstructor
public class AdminSalesController {

    private final SalesReportService salesReportService;

    @GetMapping("/admin/sales")
    public String report(@RequestParam(value = "days", required = false) Integer days,
                         @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         Model model) {
        LocalDate[] range = range(days, from, to);
        model.addAttribute("report", salesReportService.build(range[0], range[1]));
        model.addAttribute("days", (from == null && to == null) ? (days == null ? 7 : days) : null);
        model.addAttribute("from", range[0]);
        model.addAttribute("to", range[1]);
        return "admin/sales";
    }

    @GetMapping("/admin/sales.csv")
    public ResponseEntity<byte[]> csv(@RequestParam(value = "days", required = false) Integer days,
                                      @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = range(days, from, to);
        SalesReport r = salesReportService.build(range[0], range[1]);
        String name = "teagather-sales-" + range[0] + "_" + range[1] + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(salesReportService.csv(r).getBytes(StandardCharsets.UTF_8));
    }

    private static LocalDate[] range(Integer days, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now();
        if (from != null || to != null) {
            LocalDate t = to == null ? today : to;
            LocalDate f = from == null ? t.minusDays(6) : from;
            if (f.isAfter(t)) { LocalDate x = f; f = t; t = x; }
            if (ChronoUnit.DAYS.between(f, t) > 366) f = t.minusDays(366);
            return new LocalDate[]{f, t};
        }
        int n = (days == null || days < 1) ? 7 : Math.min(days, 366);
        return new LocalDate[]{today.minusDays(n - 1), today};
    }
}
