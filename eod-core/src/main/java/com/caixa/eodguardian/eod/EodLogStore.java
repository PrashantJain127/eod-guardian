package com.caixa.eodguardian.eod;

import com.caixa.eodguardian.domain.LogEntry;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded in-memory store of EOD batch log lines (simulates the FLEXCUBE EOD log / debug files). */
public class EodLogStore {
    private static final int MAX = 20_000;
    private final Deque<LogEntry> entries = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong();

    public synchronized void log(String programId, String level, String source, String message) {
        entries.addLast(new LogEntry(seq.incrementAndGet(), LocalDateTime.now(), programId, level, source, message));
        while (entries.size() > MAX) entries.removeFirst();
    }

    public synchronized List<LogEntry> forProgram(String programId, int limit) {
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry e : entries) if (programId == null || programId.equals(e.programId())) out.add(e);
        return out.size() <= limit ? out : out.subList(out.size() - limit, out.size());
    }

    public synchronized void clear() { entries.clear(); }
}
