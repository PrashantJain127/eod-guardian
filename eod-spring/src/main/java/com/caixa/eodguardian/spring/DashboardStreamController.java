package com.caixa.eodguardian.spring;

import com.caixa.eodguardian.EodGuardian;
import com.caixa.eodguardian.api.ApiFacade;
import com.caixa.eodguardian.api.ApiRequest;
import com.caixa.eodguardian.common.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Server-Sent Events: pushes the full dashboard snapshot to every connected client every 2 seconds. */
@RestController
public class DashboardStreamController {

    private record Client(SseEmitter emitter, String token) {}

    private final ApiFacade api;
    private final EodGuardian guardian;
    private final List<Client> clients = new CopyOnWriteArrayList<>();

    public DashboardStreamController(ApiFacade api) {
        this.api = api;
        this.guardian = api.guardian();
    }

    @GetMapping(path = "/api/stream", produces = "text/event-stream")
    public SseEmitter stream(HttpServletRequest request) {
        String cookie = request.getHeader("Cookie");
        String auth = request.getHeader("Authorization");
        Map<String, String> h = new java.util.HashMap<>();
        if (cookie != null) h.put("cookie", cookie);
        if (auth != null) h.put("authorization", auth);
        String token = new ApiRequest("GET", "/api/stream", Map.of(), h, "").token();
        if (guardian.auth.authenticate(token).isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required");

        SseEmitter emitter = new SseEmitter(0L);
        Client c = new Client(emitter, token);
        clients.add(c);
        guardian.metrics.increment("sse.connected");
        emitter.onCompletion(() -> remove(c));
        emitter.onTimeout(() -> remove(c));
        emitter.onError(e -> remove(c));
        send(c, Json.write(api.dashboard()));
        return emitter;
    }

    @Scheduled(fixedRate = 2000)
    public void push() {
        if (clients.isEmpty()) return;
        long t0 = System.nanoTime();
        String payload = Json.write(api.dashboard());
        for (Client c : clients) send(c, payload);
        guardian.metrics.time("sse.push", System.nanoTime() - t0);
    }

    private void send(Client c, String payload) {
        try {
            if (guardian.auth.authenticate(c.token()).isEmpty()) {
                c.emitter().complete();
                remove(c);
                return;
            }
            c.emitter().send(SseEmitter.event().name("dashboard").data(payload));
        } catch (IOException | IllegalStateException e) {
            remove(c);
        }
    }

    private void remove(Client c) {
        if (clients.remove(c)) guardian.metrics.increment("sse.disconnected");
    }
}
