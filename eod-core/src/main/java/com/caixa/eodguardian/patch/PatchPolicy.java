package com.caixa.eodguardian.patch;

import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.flexcube.FlexcubeGateway;

import java.util.*;

/**
 * Approved banking data-patch policy. A recommendation that fails any rule is never offered
 * for approval. Patchable columns are an explicit allow-list agreed with Operations Risk.
 */
public class PatchPolicy {

    private static final Map<String, Set<String>> PATCHABLE = Map.of(
            "CLTB_ACCOUNT_MASTER", Set.of("DR_PROD_AC"),
            "CSTB_PARAM", Set.of("PARAM_VAL"),
            "ACTB_DAILY_LOG", Set.of("LCY_AMOUNT"),
            "STTM_CUST_ACCOUNT", Set.of("AC_STAT_FROZEN"));

    private static final Set<String> FINANCIAL_COLUMNS = Set.of("LCY_AMOUNT", "ACY_CURR_BALANCE", "ACY_AVL_BAL");

    public List<Map<String, Object>> evaluate(DataPatch p, FlexcubeGateway gw) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> allowed = PATCHABLE.getOrDefault(p.table(), Set.of());
        rule(out, "ALLOW_LISTED_TABLE", allowed.size() > 0, "Table " + p.table() + (allowed.isEmpty() ? " is not" : " is") + " on the patchable allow-list");
        boolean colsOk = p.changes().stream().allMatch(c -> allowed.contains(c.column()));
        rule(out, "ALLOW_LISTED_COLUMNS", colsOk, "Columns " + p.changes().stream().map(DataPatch.FieldChange::column).toList()
                + (colsOk ? " permitted" : " include a non-patchable column"));
        rule(out, "UPDATE_ONLY", true, "Operation is a single-row UPDATE; INSERT/DELETE/DDL are not permitted via data patch");
        boolean fullKey;
        try {
            fullKey = gw.keyColumns(p.table()).stream().allMatch(k -> p.key().get(k) != null) && p.key().size() == gw.keyColumns(p.table()).size();
        } catch (RuntimeException e) {
            fullKey = false;
        }
        rule(out, "FULL_PRIMARY_KEY", fullKey, "WHERE clause binds the complete primary key " + p.key().keySet() + " (exactly one row)");
        boolean noKeyCols;
        try { noKeyCols = p.changes().stream().noneMatch(c -> gw.keyColumns(p.table()).contains(c.column())); }
        catch (RuntimeException e) { noKeyCols = false; }
        rule(out, "NO_KEY_MUTATION", noKeyCols, "Primary key columns are not modified");
        boolean hasPrecondition = p.changes().stream().allMatch(c -> c.currentValue() != null);
        rule(out, "OPTIMISTIC_PRECONDITION", hasPrecondition, "Every changed column carries its expected current value");
        boolean matchesNow;
        try {
            matchesNow = gw.findByKey(p.table(), p.key()).map(row -> p.changes().stream()
                    .allMatch(c -> Objects.equals(String.valueOf(row.get(c.column())), String.valueOf(c.currentValue())))).orElse(false);
        } catch (RuntimeException e) {
            matchesNow = false;
        }
        rule(out, "CURRENT_VALUES_VERIFIED", matchesNow, "Existing values in FLEXCUBE match the values shown to the reviewer");
        rule(out, "VALIDATIONS_DEFINED", !p.preChecks().isEmpty() && !p.postChecks().isEmpty(),
                p.preChecks().size() + " pre-patch and " + p.postChecks().size() + " post-patch validations defined");
        boolean financial = p.changes().stream().anyMatch(c -> FINANCIAL_COLUMNS.contains(c.column()));
        rule(out, "FINANCIAL_ENTRY_ESCALATION", !financial || "HIGH".equals(p.riskLevel()),
                financial ? "Changes a financial amount - classified HIGH risk, Finance sign-off required at approval"
                        : "No financial amount is modified");
        return out;
    }

    public static boolean allPassed(List<Map<String, Object>> checks) {
        return checks.stream().allMatch(m -> Boolean.TRUE.equals(m.get("passed")));
    }

    private static void rule(List<Map<String, Object>> out, String rule, boolean passed, String detail) {
        out.add(Maps.of("rule", rule, "passed", passed, "detail", detail));
    }
}
