package com.caixa.eodguardian.patch;

import com.caixa.eodguardian.common.Json;
import com.caixa.eodguardian.common.JsonSerializable;
import com.caixa.eodguardian.common.Maps;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * A structured, single-row UPDATE recommendation. The change is expressed as data (table, key,
 * expected current values, proposed values) and executed through the gateway with bind values -
 * never as free-form SQL - which rules out SQL injection and unintended row counts.
 */
public final class DataPatch implements JsonSerializable {

    public record FieldChange(String column, Object currentValue, Object proposedValue) {}

    private final String patchId;
    private final String investigationId;
    private final String programId;
    private final LocalDate businessDate;
    private final String title;
    private final String table;
    private final Map<String, Object> key;
    private final List<FieldChange> changes;
    private final String whatIsWrong;
    private final String affectedEntity;
    private final String justification;
    private final String expectedImpact;
    private final List<String> downstreamImplications;
    private final String riskLevel;
    private final List<String> riskNotes;
    private final List<String> alternatives;
    private final List<ValidationCheck> preChecks;
    private final List<ValidationCheck> postChecks;
    private final String recommendedBy;
    private final int confidence;
    private final Instant createdAt = Instant.now();

    private PatchStatus status = PatchStatus.RECOMMENDED;
    private List<Map<String, Object>> policyChecks = List.of();
    private Map<String, Object> criticReview = Map.of();
    private final List<Map<String, Object>> history = new ArrayList<>();
    private String maker, makerComment, checker, checkerComment, executor, rolledBackBy, rollbackReason;
    private Instant submittedAt, decidedAt, executedAt, rolledBackAt;
    private String approvedContentHash;
    private String executionIdempotencyKey;
    private Map<String, Object> executionResult;
    private List<Map<String, Object>> beforeImage = List.of();
    private List<Map<String, Object>> afterImage = List.of();

    public DataPatch(String patchId, String investigationId, String programId, LocalDate businessDate, String title,
                     String table, Map<String, Object> key, List<FieldChange> changes, String whatIsWrong,
                     String affectedEntity, String justification, String expectedImpact,
                     List<String> downstreamImplications, String riskLevel, List<String> riskNotes, List<String> alternatives,
                     List<ValidationCheck> preChecks, List<ValidationCheck> postChecks, String recommendedBy, int confidence) {
        this.patchId = patchId;
        this.investigationId = investigationId;
        this.programId = programId;
        this.businessDate = businessDate;
        this.title = title;
        this.table = table;
        this.key = Collections.unmodifiableMap(new LinkedHashMap<>(key));
        this.changes = List.copyOf(changes);
        this.whatIsWrong = whatIsWrong;
        this.affectedEntity = affectedEntity;
        this.justification = justification;
        this.expectedImpact = expectedImpact;
        this.downstreamImplications = List.copyOf(downstreamImplications);
        this.riskLevel = riskLevel;
        this.riskNotes = List.copyOf(riskNotes);
        this.alternatives = List.copyOf(alternatives);
        this.preChecks = List.copyOf(preChecks);
        this.postChecks = List.copyOf(postChecks);
        this.recommendedBy = recommendedBy;
        this.confidence = confidence;
        history.add(Maps.of("status", status, "at", createdAt, "actor", "agent:" + recommendedBy, "comment", "Recommended"));
    }

    // ------------------------------------------------------------------ derived views

    public Map<String, Object> expectedValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (FieldChange c : changes) m.put(c.column(), c.currentValue());
        return m;
    }

    public Map<String, Object> proposedValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (FieldChange c : changes) m.put(c.column(), c.proposedValue());
        return m;
    }

    /** Display-only SQL with bind placeholders; execution never uses this text. */
    public String sqlPreview() {
        StringBuilder sb = new StringBuilder("UPDATE ").append(table).append("\n   SET ");
        StringJoiner set = new StringJoiner(", ");
        for (FieldChange c : changes) set.add(c.column() + " = :new_" + c.column().toLowerCase());
        sb.append(set).append("\n WHERE ");
        StringJoiner where = new StringJoiner("\n   AND ");
        for (String k : key.keySet()) where.add(k + " = :" + k.toLowerCase());
        for (FieldChange c : changes) where.add(c.column() + " = :old_" + c.column().toLowerCase() + "   -- optimistic precondition");
        sb.append(where).append(";\n-- binds: ");
        StringJoiner binds = new StringJoiner(", ");
        key.forEach((k, v) -> binds.add(":" + k.toLowerCase() + "='" + v + "'"));
        for (FieldChange c : changes) {
            binds.add(":new_" + c.column().toLowerCase() + "='" + c.proposedValue() + "'");
            binds.add(":old_" + c.column().toLowerCase() + "='" + c.currentValue() + "'");
        }
        return sb.append(binds).append("\n-- expected rows affected: 1").toString();
    }

    public String rollbackSqlPreview() {
        StringBuilder sb = new StringBuilder("UPDATE ").append(table).append(" SET ");
        StringJoiner set = new StringJoiner(", ");
        for (FieldChange c : changes) set.add(c.column() + " = '" + c.currentValue() + "'");
        sb.append(set).append(" WHERE ");
        StringJoiner where = new StringJoiner(" AND ");
        key.forEach((k, v) -> where.add(k + " = '" + v + "'"));
        for (FieldChange c : changes) where.add(c.column() + " = '" + c.proposedValue() + "'");
        return sb.append(where).append(";").toString();
    }

    /** Hash of the content a checker approves; execution refuses if the content no longer matches. */
    public String contentHash() {
        String canon = Json.write(Maps.of("table", table, "key", key, "changes", changes, "businessDate", businessDate));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canon.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ state (mutated only by PatchService)

    synchronized void status(PatchStatus s, String actor, String comment) {
        status = s;
        history.add(Maps.of("status", s, "at", Instant.now(), "actor", actor, "comment", comment));
    }

    /** Attaches policy evaluation and critic review (done before the patch is offered to humans). */
    public synchronized void attachReview(List<Map<String, Object>> checks, Map<String, Object> critic) { policyChecks = checks; criticReview = critic; }
    synchronized void submitted(String user, String comment) { maker = user; makerComment = comment; submittedAt = Instant.now(); }
    synchronized void decided(String user, String comment, String hash) { checker = user; checkerComment = comment; decidedAt = Instant.now(); approvedContentHash = hash; }
    synchronized void executed(String user, String idem, Map<String, Object> result, List<Map<String, Object>> before, List<Map<String, Object>> after) {
        executor = user; executionIdempotencyKey = idem; executionResult = result; executedAt = Instant.now();
        if (before != null) beforeImage = before;
        if (after != null) afterImage = after;
    }
    synchronized void rolledBack(String user, String reason) { rolledBackBy = user; rollbackReason = reason; rolledBackAt = Instant.now(); }

    public synchronized PatchStatus status() { return status; }
    public synchronized String maker() { return maker; }
    public synchronized String checker() { return checker; }
    public synchronized String executor() { return executor; }
    public synchronized String approvedContentHash() { return approvedContentHash; }
    public synchronized String executionIdempotencyKey() { return executionIdempotencyKey; }
    public synchronized Map<String, Object> executionResult() { return executionResult; }
    public synchronized List<Map<String, Object>> beforeImage() { return beforeImage; }
    public synchronized List<Map<String, Object>> afterImage() { return afterImage; }
    public synchronized Instant executedAt() { return executedAt; }
    public synchronized List<Map<String, Object>> policyChecks() { return policyChecks; }

    public String patchId() { return patchId; }
    public String investigationId() { return investigationId; }
    public String programId() { return programId; }
    public String table() { return table; }
    public Map<String, Object> key() { return key; }
    public List<FieldChange> changes() { return changes; }
    public String riskLevel() { return riskLevel; }
    public String title() { return title; }
    public String affectedEntity() { return affectedEntity; }
    public String justification() { return justification; }
    public List<ValidationCheck> preChecks() { return preChecks; }
    public List<ValidationCheck> postChecks() { return postChecks; }
    public LocalDate businessDate() { return businessDate; }

    @Override
    public synchronized Map<String, Object> toMap() {
        return Maps.of(
                "patchId", patchId, "investigationId", investigationId, "programId", programId, "businessDate", businessDate,
                "title", title, "operation", "UPDATE", "table", table, "key", key, "changes", changes,
                "sqlPreview", sqlPreview(), "rollbackSqlPreview", rollbackSqlPreview(),
                "whatIsWrong", whatIsWrong, "affectedEntity", affectedEntity, "justification", justification,
                "expectedImpact", expectedImpact, "downstreamImplications", downstreamImplications,
                "riskLevel", riskLevel, "riskNotes", riskNotes, "alternatives", alternatives,
                "preChecks", preChecks, "postChecks", postChecks, "policyChecks", policyChecks, "criticReview", criticReview,
                "recommendedBy", recommendedBy, "confidence", confidence, "createdAt", createdAt,
                "status", status, "history", history,
                "maker", maker, "makerComment", makerComment, "submittedAt", submittedAt,
                "checker", checker, "checkerComment", checkerComment, "decidedAt", decidedAt,
                "executor", executor, "executedAt", executedAt, "executionIdempotencyKey", executionIdempotencyKey, "executionResult", executionResult,
                "beforeImage", beforeImage, "afterImage", afterImage,
                "rolledBackBy", rolledBackBy, "rollbackReason", rollbackReason, "rolledBackAt", rolledBackAt,
                "contentHash", contentHash(), "approvedContentHash", approvedContentHash);
    }
}
