package com.caixa.eodguardian.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concurrent-user load test against a running server. Each virtual user logs in and polls the
 * dashboard, program, investigation and metrics endpoints as the UI does.
 * Usage: gradle :eod-server:loadTest -PbaseUrl=http://localhost:8080 -Pusers=25 -Pseconds=20
 */
public final class LoadTest {
    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8080";
        int users = args.length > 1 ? Integer.parseInt(args[1]) : 25;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        String password = Optional.ofNullable(System.getenv("EOD_DEMO_PASSWORD")).orElse("Guardian@2026");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String[] logins = {"ravi.investigator", "priya.maker", "arjun.checker", "meera.operator", "sanjay.auditor"};
        String[] paths = {"/api/dashboard", "/api/eod/programs", "/api/investigations", "/api/patches", "/api/metrics"};

        Map<String, List<Long>> lat = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger(), total = new AtomicInteger();
        long end = System.currentTimeMillis() + seconds * 1000L;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int u = 0; u < users; u++) {
                final int id = u;
                pool.submit(() -> {
                    String token = login(http, base, logins[id % logins.length], password);
                    if (token == null) { errors.incrementAndGet(); return null; }
                    int i = 0;
                    while (System.currentTimeMillis() < end) {
                        String p = paths[i++ % paths.length];
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + p))
                                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
                            if (r.statusCode() != 200) errors.incrementAndGet();
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                        lat.computeIfAbsent(p, k -> Collections.synchronizedList(new ArrayList<>())).add((System.nanoTime() - t0) / 1000);
                        total.incrementAndGet();
                    }
                    return null;
                });
            }
        }
        System.out.printf("%nLoad test: %d concurrent users, %d s, %d requests, %.1f req/s, %d errors%n",
                users, seconds, total.get(), total.get() / (double) seconds, errors.get());
        System.out.printf("%-22s %8s %8s %8s %8s%n", "endpoint", "count", "p50 ms", "p95 ms", "p99 ms");
        List<Long> all = new ArrayList<>();
        for (var e : new TreeMap<>(lat).entrySet()) {
            List<Long> l = new ArrayList<>(e.getValue());
            all.addAll(l);
            print(e.getKey(), l);
        }
        print("ALL", all);
        System.exit(errors.get() == 0 ? 0 : 1);
    }

    private static void print(String name, List<Long> l) {
        Collections.sort(l);
        System.out.printf("%-22s %8d %8.1f %8.1f %8.1f%n", name, l.size(), p(l, .50), p(l, .95), p(l, .99));
    }

    private static double p(List<Long> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(q * sorted.size()) - 1)) / 1000.0;
    }

    private static String login(HttpClient http, String base, String user, String pw) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            int i = r.body().indexOf("\"token\":\"");
            return r.body().substring(i + 9, r.body().indexOf('"', i + 9));
        } catch (Exception e) {
            return null;
        }
    }
}
