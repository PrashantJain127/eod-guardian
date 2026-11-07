package com.caixa.eodguardian.audit;

import com.caixa.eodguardian.common.Json;
import com.caixa.eodguardian.common.Maps;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/**
 * Audit and Compliance store: append-only, SHA-256 hash-chained, searchable.
 * Every agent decision and human action passes through {@link #record}.
 */
public class AuditService {
    public static final String GENESIS = "0".repeat(64);

    private final AuditSink sink;
    private final Supplier<LocalDate> businessDate;
    private final List<AuditRecord> records = new ArrayList<>();
    private String lastHash = GENESIS;
    private long seq;
    private final boolean chainValidOnLoad;

    public AuditService(AuditSink sink, Supplier<LocalDate> businessDate) {
        this.sink = sink;
        this.businessDate = businessDate;
        List<AuditRecord> existing = sink.loadAll();
        records.addAll(existing);
        if (!existing.isEmpty()) {
            AuditRecord last = existing.get(existing.size() - 1);
            lastHash = last.hash();
            seq = last.seq();
        }
        chainValidOnLoad = (Boolean) verify().get("valid");
    }

    public static Event event(String type) { return new Event(type); }

    /** Fluent description of one audited event. */
    public static final class Event {
        final String type;
        String actor = "system", role = "SYSTEM", programId, investigationId, patchId, reason, approval, outcome;
        List<String> refs = new ArrayList<>();
        Object before, after;
        Map<String, Object> details = new LinkedHashMap<>();

        Event(String type) { this.type = type; }

        public Event actor(String a, String r) { actor = a; role = r; return this; }
        public Event agent(String agentName) { actor = "agent:" + agentName; role = "AI_AGENT"; return this; }
        public Event program(String p) { programId = p; return this; }
        public Event investigation(String i) { investigationId = i; return this; }
        public Event patch(String p) { patchId = p; return this; }
        public Event refs(Collection<String> r) { if (r != null) refs.addAll(r); return this; }
        public Event ref(String r) { if (r != null) refs.add(r); return this; }
        public Event before(Object b) { before = b; return this; }
        public Event after(Object a) { after = a; return this; }
        public Event reason(String r) { reason = r; return this; }
        public Event approval(String a) { approval = a; return this; }
        public Event outcome(String o) { outcome = o; return this; }
        public Event detail(String k, Object v) { details.put(k, v); return this; }
    }

    public synchronized AuditRecord record(Event e) {
        long next = seq + 1;
        // normalise values through JSON so the hash is reproducible after reload from any sink
        Object before = normalise(e.before);
        Object after = normalise(e.after);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) normalise(e.details);
        AuditRecord draft = new AuditRecord(next, "AUD-" + String.format("%07d", next), Instant.now(), businessDate.get(),
                e.type, e.actor, e.role, e.programId, e.investigationId, e.patchId, List.copyOf(e.refs), before, after,
                e.reason, e.approval, e.outcome, details == null ? Map.of() : details, lastHash, null);
        String hash = sha256(draft.canonicalPayload());
        AuditRecord rec = new AuditRecord(draft.seq(), draft.auditId(), draft.timestamp(), draft.businessDate(), draft.eventType(),
                draft.actor(), draft.actorRole(), draft.programId(), draft.investigationId(), draft.patchId(), draft.entityRefs(),
                draft.before(), draft.after(), draft.reason(), draft.approval(), draft.outcome(), draft.details(), draft.prevHash(), hash);
        sink.append(rec); // persist first: if the journal fails the action fails
        records.add(rec);
        seq = next;
        lastHash = hash;
        return rec;
    }

    private static Object normalise(Object o) { return o == null ? null : Json.parse(Json.write(o)); }

    /** Searchable audit history. All filters are optional, case-insensitive "contains" except dates. */
    public synchronized List<AuditRecord> search(Map<String, String> f, int limit) {
        String bd = f.get("businessDate"), program = f.get("programId"), entity = f.get("entity"), user = f.get("user"),
                patch = f.get("patchId"), approval = f.get("approval"), outcome = f.get("outcome"), type = f.get("eventType"),
                text = f.get("q"), inv = f.get("investigationId");
        List<AuditRecord> out = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0 && out.size() < limit; i--) {
            AuditRecord r = records.get(i);
            if (notBlank(bd) && (r.businessDate() == null || !r.businessDate().toString().equals(bd))) continue;
            if (notBlank(program) && !containsIc(r.programId(), program)) continue;
            if (notBlank(entity) && r.entityRefs().stream().noneMatch(x -> containsIc(x, entity))) continue;
            if (notBlank(user) && !containsIc(r.actor(), user)) continue;
            if (notBlank(patch) && !containsIc(r.patchId(), patch)) continue;
            if (notBlank(approval) && !containsIc(r.approval(), approval)) continue;
            if (notBlank(outcome) && !containsIc(r.outcome(), outcome)) continue;
            if (notBlank(type) && !containsIc(r.eventType(), type)) continue;
            if (notBlank(inv) && !containsIc(r.investigationId(), inv)) continue;
            if (notBlank(text) && !containsIc(r.canonicalPayload(), text)) continue;
            out.add(r);
        }
        return out;
    }

    public synchronized List<AuditRecord> all() { return List.copyOf(records); }

    public synchronized int size() { return records.size(); }

    /** Re-computes the whole chain. Any edited, inserted or deleted record makes it invalid. */
    public synchronized Map<String, Object> verify() {
        String prev = GENESIS;
        long expectedSeq = 1;
        for (AuditRecord r : records) {
            if (r.seq() != expectedSeq || !prev.equals(r.prevHash()) || !sha256(r.canonicalPayload()).equals(r.hash())) {
                return Maps.of("valid", false, "records", records.size(), "brokenAt", r.auditId(), "sink", sink.description());
            }
            prev = r.hash();
            expectedSeq++;
        }
        return Maps.of("valid", true, "records", records.size(), "headHash", prev, "sink", sink.description());
    }

    public boolean chainValidOnLoad() { return chainValidOnLoad; }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static boolean containsIc(String v, String needle) {
        return v != null && v.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
