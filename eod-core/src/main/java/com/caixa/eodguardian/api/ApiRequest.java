package com.caixa.eodguardian.api;

import java.util.Map;

/** Transport-neutral HTTP request (headers keys are lower-case). */
public record ApiRequest(String method, String path, Map<String, String> query, Map<String, String> headers, String body) {

    public String header(String name) { return headers.get(name.toLowerCase()); }

    /** Session token from "Authorization: Bearer" or the HttpOnly session cookie. */
    public String token() {
        String auth = header("authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) return auth.substring(7).trim();
        String cookie = header("cookie");
        if (cookie != null)
            for (String c : cookie.split(";")) {
                String[] kv = c.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(ApiFacade.SESSION_COOKIE)) return kv[1];
            }
        return null;
    }
}
