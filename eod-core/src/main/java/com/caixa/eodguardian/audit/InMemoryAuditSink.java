package com.caixa.eodguardian.audit;

import java.util.ArrayList;
import java.util.List;

/** Volatile sink for tests and demos. */
public class InMemoryAuditSink implements AuditSink {
    private final List<AuditRecord> list = new ArrayList<>();

    @Override public synchronized void append(AuditRecord record) { list.add(record); }

    @Override public synchronized List<AuditRecord> loadAll() { return new ArrayList<>(list); }

    /** Test hook to prove tamper evidence: replaces a stored record without re-hashing. */
    public synchronized void tamper(int index, AuditRecord forged) { list.set(index, forged); }

    @Override public String description() { return "in-memory (volatile)"; }
}
