package com.teagather.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 막대 그래프 한 칸 (CSS 막대 : 높이·폭 = pct %)
@Getter
@AllArgsConstructor
public class Bar {
    private final String label;
    private final long value;
    private final int pct;              // 가장 큰 값 대비 0~100
    private final boolean highlight;    // 오늘 등 강조 (갈색)
    private final String sub;           // 보조 글자 (선택)
}
