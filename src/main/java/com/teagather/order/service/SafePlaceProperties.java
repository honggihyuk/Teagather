package com.teagather.order.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

// app.order.safe-place.* : 주문 재시도 스위치 (enabled=false 면 한 번만 시도)
@ConfigurationProperties(prefix = "app.order.safe-place")
public record SafePlaceProperties(Boolean enabled, Integer maxRetries) {

    public SafePlaceProperties {
        if (enabled == null) enabled = true;
        if (maxRetries == null || maxRetries < 0) maxRetries = 2;
    }

    public int effectiveRetries() {
        return enabled ? maxRetries : 0;
    }
}
