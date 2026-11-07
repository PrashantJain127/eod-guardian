package com.caixa.eodguardian.api;

import com.caixa.eodguardian.common.Json;
import com.caixa.eodguardian.common.Maps;

import java.util.LinkedHashMap;
import java.util.Map;

public record ApiResponse(int status, String body, Map<String, String> headers) {

    public static ApiResponse json(int status, Object payload) {
        return new ApiResponse(status, Json.write(payload), new LinkedHashMap<>());
    }

    public static ApiResponse ok(Object payload) { return json(200, payload); }

    public static ApiResponse error(int status, String code, String message) {
        return json(status, Maps.of("error", code, "message", message, "status", status));
    }

    public ApiResponse header(String k, String v) { headers.put(k, v); return this; }
}
