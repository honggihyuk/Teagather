package com.teagather.common.util;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;

// JSON 읽기·쓰기 (Spring Boot 4 = Jackson 3 : tools.jackson 패키지)
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {}

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static void write(File file, Object value) {
        MAPPER.writeValue(file, value);
    }

    public static <T> T read(File file, Class<T> type) {
        return MAPPER.readValue(file, type);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
