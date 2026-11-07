package com.caixa.eodguardian.audit;

import java.util.List;

/**
 * Append-only persistence for audit records. Implementations must never update or delete.
 * Shipped: {@link JsonlFileAuditSink} (standalone) and a JDBC/H2 sink in the Spring module.
 */
public interface AuditSink {
    void append(AuditRecord record);

    /** Loads all persisted records in sequence order (used to rebuild and verify the chain on startup). */
    List<AuditRecord> loadAll();

    default String description() { return getClass().getSimpleName(); }
}
