package com.caixa.eodguardian.audit;

import com.caixa.eodguardian.common.JsonSerializable;
import com.caixa.eodguardian.common.Maps;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Immutable audit record. {@code hash} = SHA-256(prevHash + canonical payload), so any
 * modification or deletion of an earlier record breaks the chain (tamper evidence).
 */
public record AuditRecord(long seq, String auditId, Instant timestamp, LocalDate businessDate, String eventType,
                          String actor, String actorRole, String programId, String investigationId, String patchId,
                          List<String> entityRefs, Object before, Object after, String reason, String approval,
                          String outcome, Map<String, Object> details, String prevHash, String hash)
        implements JsonSerializable {

    /** Canonical payload hashed into the chain (everything except the hash itself). */
    public String canonicalPayload() {
        return com.caixa.eodguardian.common.Json.write(Maps.of(
                "seq", seq, "auditId", auditId, "timestamp", timestamp, "businessDate", businessDate,
                "eventType", eventType, "actor", actor, "actorRole", actorRole, "programId", programId,
                "investigationId", investigationId, "patchId", patchId, "entityRefs", entityRefs,
                "before", before, "after", after, "reason", reason, "approval", approval, "outcome", outcome,
                "details", details, "prevHash", prevHash));
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> m = com.caixa.eodguardian.common.Json.parseObject(canonicalPayload());
        m.put("hash", hash);
        return m;
    }
}
