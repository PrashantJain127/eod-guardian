package com.caixa.eodguardian.server;

import com.caixa.eodguardian.EodGuardian;
import com.caixa.eodguardian.api.ApiFacade;
import com.caixa.eodguardian.api.ApiRequest;
import com.caixa.eodguardian.api.ApiResponse;
import com.caixa.eodguardian.audit.JsonlFileAuditSink;
import com.caixa.eodguardian.common.GuardianConfig;
import com.caixa.eodguardian.common.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone runtime: JDK HttpServer (virtual threads) serving the REST API, a Server-Sent
 * Events stream that pushes the dashboard every 2 seconds, and the React bundle from the classpath.
 * Zero third-party dependencies.
 */
public final class EodGuardianServer {
    private static final Logger LOG = Logger.getLogger(EodGuardianServer.class.getName());
    private static final long PUSH_INTERVAL_MS = 2000;
    private static final int MAX_BODY = 64 * 1024;

    private final ApiFacade api;
    private final HttpServer http;
    private final Set<SseClient> clients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-pusher");
        t.setDaemon(true);
        return t;
    });

    private record SseClient(HttpExchange ex, OutputStream out, String token) {}

    public EodGuardianServer(EodGuardian guardian, int port) throws IOException {
        this.api = new ApiFacade(guardian);
        this.http = HttpServer.create(new InetSocketAddress(port), 256);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/api/stream", this::stream);
        http.createContext("/api/", this::api);
        http.createContext("/", this::staticFile);
    }

    public void start() {
        http.start();
        pusher.scheduleAtFixedRate(this::pushAll, PUSH_INTERVAL_MS, PUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public int port() { return http.getAddress().getPort(); }

    public void stop() {
        pusher.shutdownNow();
        http.stop(0);
    }

    // ------------------------------------------------------------------ REST

    private void api(HttpExchange ex) throws IOException {
        try {
            byte[] raw = readBody(ex);
            if (raw == null) { send(ex, ApiResponse.error(413, "PAYLOAD_TOO_LARGE", "Request body exceeds 64 KB")); return; }
            Map<String, String> headers = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
            ApiRequest req = new ApiRequest(ex.getRequestMethod(), ex.getRequestURI().getPath(), query(ex.getRequestURI().getRawQuery()),
                    headers, new String(raw, StandardCharsets.UTF_8));
            send(ex, api.handle(req));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Request failed", e);
            send(ex, ApiResponse.error(500, "INTERNAL_ERROR", "Request failed"));
        }
    }

    private static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            return b.length > MAX_BODY ? null : b;
        }
    }

    private static void send(HttpExchange ex, ApiResponse r) throws IOException {
        byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        securityHeaders(h);
        h.set("Cache-Control", "no-store");
        r.headers().forEach(h::add);
        ex.sendResponseHeaders(r.status(), body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static void securityHeaders(com.sun.net.httpserver.Headers h) {
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'");
    }

    // ------------------------------------------------------------------ SSE (2-second push)

    private void stream(HttpExchange ex) throws IOException {
        Map<String, String> headers = new HashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        String token = new ApiRequest("GET", "/api/stream", Map.of(), headers, "").token();
        if (api.guardian().auth.authenticate(token).isEmpty()) {
            send(ex, ApiResponse.error(401, "UNAUTHORIZED", "Login required"));
            return;
        }
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-cache");
        h.set("Connection", "keep-alive");
        h.set("X-Accel-Buffering", "no");
        securityHeaders(h);
        ex.sendResponseHeaders(200, 0);
        SseClient c = new SseClient(ex, ex.getResponseBody(), token);
        clients.add(c);
        api.guardian().metrics.increment("sse.connected");
        push(c, Json.write(api.dashboard())); // immediate first frame
    }

    private void pushAll() {
        if (clients.isEmpty()) return;
        long t0 = System.nanoTime();
        String payload = Json.write(api.dashboard());
        for (SseClient c : clients) push(c, payload);
        api.guardian().metrics.time("sse.push", System.nanoTime() - t0);
    }

    private void push(SseClient c, String payload) {
        try {
            if (api.guardian().auth.authenticate(c.token()).isEmpty()) throw new IOException("session expired");
            synchronized (c) {
                c.out().write(("event: dashboard\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                c.out().flush();
            }
        } catch (IOException e) {
            if (clients.remove(c)) {
                api.guardian().metrics.increment("sse.disconnected");
                try { c.ex().close(); } catch (Exception ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------ static UI

    private void staticFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.contains("..")) { send(ex, ApiResponse.error(400, "BAD_PATH", "Invalid path")); return; }
        if (path.equals("/") || !path.contains(".")) path = "/index.html"; // SPA fallback
        try (InputStream in = EodGuardianServer.class.getResourceAsStream("/static" + path)) {
            if (in == null) { send(ex, ApiResponse.error(404, "NOT_FOUND", "Not found")); return; }
            byte[] b = in.readAllBytes();
            var h = ex.getResponseHeaders();
            h.set("Content-Type", contentType(path));
            securityHeaders(h);
            h.set("Cache-Control", path.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-cache");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        }
    }

    private static String contentType(String p) {
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            q.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return q;
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s %3$s - %5$s%6$s%n");
        GuardianConfig cfg = GuardianConfig.fromEnvironment();
        EodGuardian guardian = new EodGuardian(cfg, new JsonlFileAuditSink(Path.of(cfg.dataDir(), "audit-journal.jsonl")));
        EodGuardianServer server = new EodGuardianServer(guardian, cfg.port());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(); guardian.close(); }));
        LOG.info("EOD Guardian listening on http://localhost:" + server.port() + "  (demo users: see README; password from EOD_DEMO_PASSWORD)");
    }
}
