package com.teagather.analysis.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 분석을 백그라운드에서 돌린다 : 관리자 버튼 · 서버 시작 · 매일 자동
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisLauncher {

    private final AnalysisService analysisService;

    @Value("${app.analysis.run-on-startup:true}")
    private boolean runOnStartup;

    @Async
    public void runAsync(String triggeredBy) {
        analysisService.run(triggeredBy);
    }

    // 서버 시작 : 성공한 분석이 하나도 없으면 한 번 실행 (예시 데이터를 만든 뒤)
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (runOnStartup && analysisService.latestSuccess().isEmpty()) {
            log.info("=======> 분석 결과가 없어 처음 분석을 시작합니다");
            analysisService.run("startup");
        }
    }

    @Scheduled(cron = "${app.analysis.cron:0 0 5 * * *}")
    public void daily() {
        analysisService.run("schedule");
    }
}
