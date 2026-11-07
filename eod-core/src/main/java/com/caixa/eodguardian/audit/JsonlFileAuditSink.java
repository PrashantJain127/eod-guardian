package com.caixa.eodguardian.audit;

import com.caixa.eodguardian.common.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Append-only JSON-lines audit journal (fsync per record). */
public class JsonlFileAuditSink implements AuditSink {
    private final Path file;

    public JsonlFileAuditSink(Path file) {
        this.file = file;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void append(AuditRecord r) {
        try (var ch = java.nio.channels.FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap((Json.write(r.toMap()) + "\n").getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Audit journal write failed - refusing to continue", e);
        }
    }

    @Override
    public synchronized List<AuditRecord> loadAll() {
        if (!Files.exists(file)) return List.of();
        List<AuditRecord> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                out.add(fromMap(Json.parseObject(line)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static AuditRecord fromMap(Map<String, Object> m) {
        List<String> refs = new ArrayList<>();
        for (Object o : Json.arr(m, "entityRefs")) refs.add(String.valueOf(o));
        Object details = m.get("details");
        return new AuditRecord(((Number) m.get("seq")).longValue(), Json.str(m, "auditId"), Instant.parse(Json.str(m, "timestamp")),
                m.get("businessDate") == null ? null : LocalDate.parse(Json.str(m, "businessDate")), Json.str(m, "eventType"),
                Json.str(m, "actor"), Json.str(m, "actorRole"), Json.str(m, "programId"), Json.str(m, "investigationId"),
                Json.str(m, "patchId"), refs, m.get("before"), m.get("after"), Json.str(m, "reason"), Json.str(m, "approval"),
                Json.str(m, "outcome"), details instanceof Map ? (Map<String, Object>) details : Map.of(),
                Json.str(m, "prevHash"), Json.str(m, "hash"));
    }

    @Override
    public String description() { return "JSONL journal at " + file.toAbsolutePath(); }
}
