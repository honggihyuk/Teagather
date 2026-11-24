package com.teagather;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// Teagather : 카페 온라인 스토어 (고객 사이트 + 관리자 + PyTorch 분석)
@EnableAsync
@EnableScheduling
@SpringBootApplication
public class TeagatherApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeagatherApplication.class, args);
    }
}
