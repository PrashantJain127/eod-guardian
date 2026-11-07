package com.caixa.eodguardian.spring;

import com.caixa.eodguardian.api.ApiFacade;
import com.caixa.eodguardian.api.ApiRequest;
import com.caixa.eodguardian.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Single delegating controller: every /api route is served by the transport-neutral
 * {@link ApiFacade}, so the Spring and standalone runtimes behave identically.
 */
@RestController
public class ApiController {

    private final ApiFacade api;

    public ApiController(ApiFacade api) { this.api = api; }

    @RequestMapping("/api/**")
    public ResponseEntity<String> handle(HttpServletRequest request, @RequestBody(required = false) String body) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames()))
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        Map<String, String> query = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> query.put(k, v.length > 0 ? v[0] : ""));

        ApiResponse r = api.handle(new ApiRequest(request.getMethod(), request.getRequestURI(), query, headers, body == null ? "" : body));

        HttpHeaders out = new HttpHeaders();
        out.setContentType(MediaType.APPLICATION_JSON);
        out.setCacheControl("no-store");
        r.headers().forEach(out::add);
        return new ResponseEntity<>(r.body(), out, r.status());
    }
}
