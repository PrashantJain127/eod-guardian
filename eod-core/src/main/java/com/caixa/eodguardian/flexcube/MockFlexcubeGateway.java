package com.caixa.eodguardian.flexcube;

import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.domain.HistoricalIncident;
import com.caixa.eodguardian.domain.ProgramRunHistory;
import com.caixa.eodguardian.domain.Scenario;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-memory simulation of the FLEXCUBE tables the EOD programs and agents need.
 * Table names and columns follow FLEXCUBE UBS naming (STTM_CUST_ACCOUNT, CLTB_ACCOUNT_MASTER,
 * CSTB_PARAM, CYTM_RATES, ACTB_DAILY_LOG ...). A configurable I/O latency simulates
 * database round trips so that parallel agent execution is measurable.
 */
public class MockFlexcubeGateway implements FlexcubeGateway {

    static final class Table {
        final String name;
        final List<String> columns;
        final List<String> keyColumns;
        final LinkedHashMap<String, Map<String, Object>> rows = new LinkedHashMap<>();

        Table(String name, List<String> keyColumns, List<String> columns) {
            this.name = name;
            this.keyColumns = keyColumns;
            this.columns = columns;
        }

        String keyOf(Map<String, Object> row) {
            StringBuilder sb = new StringBuilder();
            for (String k : keyColumns) sb.append(row.get(k)).append('|');
            return sb.toString();
        }
    }

    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<HistoricalIncident> incidents = new ArrayList<>();
    private final Map<String, List<ProgramRunHistory>> history = new HashMap<>();
    private final List<Map<String, Object>> paramChangeLog = new ArrayList<>();
    private final Map<String, String> paramBaseline = new LinkedHashMap<>();
    private final int ioLatencyMs;
    private volatile LocalDate businessDate;
    private final String branchCode = "000";

    public MockFlexcubeGateway(int ioLatencyMs) {
        this.ioLatencyMs = Math.max(0, ioLatencyMs);
    }

    /** Re-seeds every table for a scenario (called when an EOD run is started/reset). */
    public void reseed(Scenario scenario, LocalDate businessDate) {
        lock.writeLock().lock();
        try {
            tables.clear();
            incidents.clear();
            history.clear();
            paramChangeLog.clear();
            paramBaseline.clear();
            this.businessDate = businessDate;
            SimulatedFlexcubeData.seed(this, scenario, businessDate);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------- seeding API (package-private)

    void defineTable(String name, List<String> keys, List<String> columns) {
        tables.put(name, new Table(name, keys, columns));
    }

    void insert(String table, Map<String, Object> row) {
        Table t = table(table);
        for (String c : row.keySet())
            if (!t.columns.contains(c)) throw new IllegalArgumentException(table + " has no column " + c);
        Map<String, Object> copy = new LinkedHashMap<>();
        for (String c : t.columns) copy.put(c, row.get(c));
        t.rows.put(t.keyOf(copy), copy);
    }

    void addIncident(HistoricalIncident i) { incidents.add(i); }
    void addHistory(ProgramRunHistory h) { history.computeIfAbsent(h.programId(), k -> new ArrayList<>()).add(h); }
    void addParamChange(Map<String, Object> change) { paramChangeLog.add(change); }
    void baseline(String param, String value) { paramBaseline.put(param, value); }

    // ---------------------------------------------------------------- FlexcubeGateway

    @Override public LocalDate businessDate() { return businessDate; }
    @Override public String branchCode() { return branchCode; }

    @Override
    public Set<String> tables() {
        lock.readLock().lock();
        try { return new LinkedHashSet<>(tables.keySet()); } finally { lock.readLock().unlock(); }
    }

    @Override
    public List<String> columns(String table) { return List.copyOf(table(table).columns); }

    @Override
    public List<String> keyColumns(String table) { return List.copyOf(table(table).keyColumns); }

    @Override
    public List<Map<String, Object>> query(String table, Map<String, Object> filter) {
        simulateIo();
        lock.readLock().lock();
        try {
            Table t = table(table);
            Map<String, Object> f = filter == null ? Map.of() : filter;
            for (String c : f.keySet()) validateColumn(t, c);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : t.rows.values()) {
                boolean match = true;
                for (Map.Entry<String, Object> e : f.entrySet()) {
                    if (!valueEquals(row.get(e.getKey()), e.getValue())) { match = false; break; }
                }
                if (match) out.add(new LinkedHashMap<>(row));
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<Map<String, Object>> findByKey(String table, Map<String, Object> key) {
        simulateIo();
        lock.readLock().lock();
        try {
            Table t = table(table);
            requireFullKey(t, key);
            Map<String, Object> row = t.rows.get(t.keyOf(key));
            return Optional.ofNullable(row == null ? null : new LinkedHashMap<>(row));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public TransactionResult applyInTransaction(List<RowUpdate> updates) {
        simulateIo();
        lock.writeLock().lock();
        // Undo log: restore exact prior images if anything fails mid-batch.
        Map<String, Map<String, Object>> undo = new LinkedHashMap<>();
        List<Map<String, Object>> before = new ArrayList<>();
        List<Map<String, Object>> after = new ArrayList<>();
        try {
            for (RowUpdate u : updates) {
                Table t = table(u.table());
                requireFullKey(t, u.key());
                for (String c : u.newValues().keySet()) {
                    validateColumn(t, c);
                    if (t.keyColumns.contains(c))
                        throw GuardianException.badRequest("KEY_UPDATE_FORBIDDEN", "Primary key column " + c + " cannot be patched");
                }
                String k = t.keyOf(u.key());
                Map<String, Object> row = t.rows.get(k);
                if (row == null) throw GuardianException.conflict("ROW_NOT_FOUND", "No row in " + t.name + " for key " + u.key());
                for (Map.Entry<String, Object> e : u.expected().entrySet()) {
                    if (!valueEquals(row.get(e.getKey()), e.getValue()))
                        throw GuardianException.conflict("PRECONDITION_FAILED",
                                t.name + "." + e.getKey() + " expected '" + e.getValue() + "' but found '" + row.get(e.getKey())
                                        + "' - data changed since the patch was approved");
                }
                undo.putIfAbsent(t.name + "#" + k, new LinkedHashMap<>(row));
                before.add(withTable(t.name, row));
                row.putAll(u.newValues());
                after.add(withTable(t.name, row));
            }
            return new TransactionResult(true, updates.size(), before, after, "Committed");
        } catch (RuntimeException ex) {
            for (Map.Entry<String, Map<String, Object>> e : undo.entrySet()) {
                String[] parts = e.getKey().split("#", 2);
                tables.get(parts[0]).rows.put(parts[1], e.getValue());
            }
            throw ex;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override public List<HistoricalIncident> historicalIncidents() { return List.copyOf(incidents); }

    @Override
    public List<ProgramRunHistory> runHistory(String programId) {
        return List.copyOf(history.getOrDefault(programId, List.of()));
    }

    @Override
    public List<Map<String, Object>> parameterChangeLog() {
        simulateIo();
        lock.readLock().lock();
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> m : paramChangeLog) out.add(new LinkedHashMap<>(m));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override public Map<String, String> parameterBaseline() { return Map.copyOf(paramBaseline); }

    /** Direct (latency-free, unaudited) mutation used only by the simulator to model user activity. */
    public void simulatorUpdate(String table, Map<String, Object> key, Map<String, Object> values) {
        lock.writeLock().lock();
        try {
            Table t = table(table);
            Map<String, Object> row = t.rows.get(t.keyOf(key));
            if (row != null) row.putAll(values);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------- helpers

    private Table table(String name) {
        if (name == null) throw GuardianException.badRequest("UNKNOWN_TABLE", "Table is required");
        Table t = tables.get(name.toUpperCase(Locale.ROOT));
        if (t == null) throw GuardianException.badRequest("UNKNOWN_TABLE", "Table not in approved schema: " + name);
        return t;
    }

    private static void validateColumn(Table t, String c) {
        if (!t.columns.contains(c)) throw GuardianException.badRequest("UNKNOWN_COLUMN", t.name + " has no column " + c);
    }

    private static void requireFullKey(Table t, Map<String, Object> key) {
        for (String k : t.keyColumns)
            if (key == null || key.get(k) == null)
                throw GuardianException.badRequest("INCOMPLETE_KEY", t.name + " requires key column " + k);
    }

    private static Map<String, Object> withTable(String table, Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_table", table);
        m.putAll(row);
        return m;
    }

    static boolean valueEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number na && b instanceof Number nb)
            return new java.math.BigDecimal(na.toString()).compareTo(new java.math.BigDecimal(nb.toString())) == 0;
        if (a instanceof Number na && b instanceof String sb) {
            try { return new java.math.BigDecimal(na.toString()).compareTo(new java.math.BigDecimal(sb)) == 0; }
            catch (NumberFormatException e) { return false; }
        }
        if (b instanceof Number) return valueEquals(b, a);
        return a.toString().equals(b.toString());
    }

    private void simulateIo() {
        if (ioLatencyMs <= 0) return;
        int jitter = ThreadLocalRandom.current().nextInt(Math.max(1, ioLatencyMs / 2));
        try { Thread.sleep(ioLatencyMs + jitter); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
