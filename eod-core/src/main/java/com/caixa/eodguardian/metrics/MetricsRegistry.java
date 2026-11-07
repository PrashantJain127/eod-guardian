package com.caixa.eodguardian.metrics;

import com.caixa.eodguardian.common.Maps;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/** Lightweight in-process metrics: latency timers with percentiles, counters and gauges. */
public class MetricsRegistry {
    private static final int WINDOW = 5_000;

    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, DoubleAdder> sums = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Long> requestTimes = new ConcurrentLinkedDeque<>();

    public static final class Timer {
        private final long[] samples = new long[WINDOW];
        private int idx;
        private long count;
        private long totalMicros;
        private long maxMicros;

        synchronized void record(long micros) {
            samples[idx] = micros;
            idx = (idx + 1) % WINDOW;
            count++;
            totalMicros += micros;
            maxMicros = Math.max(maxMicros, micros);
        }

        synchronized Map<String, Object> snapshot() {
            int n = (int) Math.min(count, WINDOW);
            long[] copy = Arrays.copyOf(samples, n);
            Arrays.sort(copy);
            return Maps.of("count", count,
                    "meanMs", count == 0 ? 0 : round(totalMicros / 1000.0 / count),
                    "p50Ms", pct(copy, 0.50), "p95Ms", pct(copy, 0.95), "p99Ms", pct(copy, 0.99),
                    "maxMs", round(maxMicros / 1000.0));
        }

        private static double pct(long[] sorted, double p) {
            if (sorted.length == 0) return 0;
            int i = (int) Math.ceil(p * sorted.length) - 1;
            return round(sorted[Math.max(0, Math.min(i, sorted.length - 1))] / 1000.0);
        }
    }

    static double round(double v) { return Math.round(v * 100.0) / 100.0; }

    public void time(String name, long nanos) { timers.computeIfAbsent(name, k -> new Timer()).record(nanos / 1000); }

    public void increment(String name) { add(name, 1); }

    public void add(String name, long delta) { counters.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(delta); }

    public void addDouble(String name, double v) { sums.computeIfAbsent(name, k -> new DoubleAdder()).add(v); }

    public long counter(String name) { AtomicLong a = counters.get(name); return a == null ? 0 : a.get(); }

    public double sum(String name) { DoubleAdder d = sums.get(name); return d == null ? 0 : d.sum(); }

    /** Records an API request for throughput calculation (rolling 60 s window). */
    public void markRequest() {
        long now = System.currentTimeMillis();
        requestTimes.addLast(now);
        while (!requestTimes.isEmpty() && requestTimes.peekFirst() < now - 60_000) requestTimes.pollFirst();
    }

    public double throughputPerSecond() {
        long now = System.currentTimeMillis();
        long n = requestTimes.stream().filter(t -> t >= now - 60_000).count();
        return round(n / 60.0);
    }

    public Map<String, Object> timers(String prefix) {
        Map<String, Object> out = new TreeMap<>();
        timers.forEach((k, v) -> { if (k.startsWith(prefix)) out.put(k.substring(prefix.length()), v.snapshot()); });
        return out;
    }

    public Map<String, Object> counters() {
        Map<String, Object> out = new TreeMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        sums.forEach((k, v) -> out.put(k, round(v.sum() * 10000) / 10000.0));
        return out;
    }
}
