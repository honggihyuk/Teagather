package com.teagather.admin.dto;

import com.teagather.analysis.entity.AnalysisRun;
import com.teagather.analysis.entity.MenuInsight;
import com.teagather.order.entity.CustomerOrder;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

// 관리자 대시보드
@Getter
@Builder
public class AdminDashboard {
    private final long todayRevenue;
    private final long yesterdayRevenue;
    private final Integer changePct;    // 어제 같은 시각까지 대비 (없으면 null)
    private final int todayOrders;
    private final long openOrders;      // 접수 + 준비 중 + 픽업 대기
    private final long newCustomers;    // 이번 달 가입
    private final long customers;
    private final List<Bar> week;       // 최근 7일 매출
    private final List<Bar> topMenus;   // 최근 7일 많이 팔린 메뉴
    private final List<CustomerOrder> recentOpen;
    private final AnalysisRun run;
    private final List<MenuInsight> topPicks;   // 할인 · 오늘의 메뉴 · 신메뉴 후보 각 1위
}
