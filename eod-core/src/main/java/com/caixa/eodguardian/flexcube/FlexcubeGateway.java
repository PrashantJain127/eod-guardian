package com.caixa.eodguardian.flexcube;

import com.caixa.eodguardian.domain.HistoricalIncident;
import com.caixa.eodguardian.domain.ProgramRunHistory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Port to the FLEXCUBE data layer. Agents only get read access through this interface;
 * the single write path ({@link #applyInTransaction}) is invoked exclusively by the
 * Data Patch Execution Agent after maker-checker approval.
 *
 * <p>The shipped implementation is {@link MockFlexcubeGateway}. A JDBC/REST implementation
 * against an approved FLEXCUBE interface can be plugged in without touching the agents.</p>
 */
public interface FlexcubeGateway {

    LocalDate businessDate();

    String branchCode();

    Set<String> tables();

    List<String> columns(String table);

    /** Read-only equality query. Table and columns are validated against the schema whitelist. */
    List<Map<String, Object>> query(String table, Map<String, Object> equalsFilter);

    Optional<Map<String, Object>> findByKey(String table, Map<String, Object> key);

    List<String> keyColumns(String table);

    /**
     * Applies all row updates atomically. Each update carries the values it expects to find
     * (optimistic precondition); any mismatch rolls back every change in the batch.
     */
    TransactionResult applyInTransaction(List<RowUpdate> updates);

    List<HistoricalIncident> historicalIncidents();

    List<ProgramRunHistory> runHistory(String programId);

    /** Parameter maintenance change log (who changed what, when). */
    List<Map<String, Object>> parameterChangeLog();

    /** Approved parameter baseline from the last successful, signed-off EOD. */
    Map<String, String> parameterBaseline();

    record RowUpdate(String table, Map<String, Object> key, Map<String, Object> expected, Map<String, Object> newValues) {}

    record TransactionResult(boolean committed, int rowsAffected, List<Map<String, Object>> beforeImages,
                             List<Map<String, Object>> afterImages, String message) {}
}
