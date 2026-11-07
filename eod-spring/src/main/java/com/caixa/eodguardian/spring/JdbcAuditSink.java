package com.caixa.eodguardian.spring;

import com.caixa.eodguardian.audit.AuditRecord;
import com.caixa.eodguardian.audit.AuditSink;
import com.caixa.eodguardian.audit.JsonlFileAuditSink;
import com.caixa.eodguardian.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;

/**
 * Append-only JDBC audit store (H2 by default, PostgreSQL-compatible DDL in schema.sql).
 * The full canonical record is stored as JSON together with its chain hash; the application
 * only ever INSERTs. In production grant the application user INSERT/SELECT only on EOD_AUDIT.
 */
public class JdbcAuditSink implements AuditSink {
    private final JdbcTemplate jdbc;

    public JdbcAuditSink(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void append(AuditRecord r) {
        jdbc.update("INSERT INTO EOD_AUDIT (SEQ, AUDIT_ID, EVENT_TS, BUSINESS_DATE, EVENT_TYPE, ACTOR, PROGRAM_ID, PATCH_ID, "
                        + "INVESTIGATION_ID, OUTCOME, PREV_HASH, HASH, PAYLOAD) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.seq(), r.auditId(), Timestamp.from(r.timestamp()),
                r.businessDate() == null ? null : java.sql.Date.valueOf(r.businessDate()),
                r.eventType(), r.actor(), r.programId(), r.patchId(), r.investigationId(), r.outcome(),
                r.prevHash(), r.hash(), Json.write(r.toMap()));
    }

    @Override
    public List<AuditRecord> loadAll() {
        return jdbc.query("SELECT PAYLOAD FROM EOD_AUDIT ORDER BY SEQ",
                (rs, i) -> JsonlFileAuditSink.fromMap(Json.parseObject(rs.getString(1))));
    }

    @Override
    public String description() { return "JDBC table EOD_AUDIT"; }
}
