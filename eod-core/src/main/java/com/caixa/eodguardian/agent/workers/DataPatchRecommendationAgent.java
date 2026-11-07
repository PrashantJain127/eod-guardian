package com.caixa.eodguardian.agent.workers;

import com.caixa.eodguardian.agent.AgentResult;
import com.caixa.eodguardian.agent.AgentTask;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.eod.EodProgramException;
import com.caixa.eodguardian.eod.SimulatedEodEngine;
import com.caixa.eodguardian.flexcube.FlexcubeGateway;
import com.caixa.eodguardian.patch.DataPatch;
import com.caixa.eodguardian.patch.ValidationCheck;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

/**
 * G. Data Patch Recommendation Agent - turns an accepted root cause into a structured,
 * policy-compliant single-row correction with before/after values, business justification,
 * impact, downstream implications and executable pre/post validations. It only recommends;
 * it has no write access.
 */
public class DataPatchRecommendationAgent extends BaseAgent {

    private final SimulatedEodEngine engine;
    private final Supplier<String> ids;

    public DataPatchRecommendationAgent(FlexcubeGateway gateway, SimulatedEodEngine engine, Supplier<String> ids) {
        super(gateway);
        this.engine = engine;
        this.ids = ids;
    }

    @Override public String name() { return "DataPatchRecommendationAgent"; }
    @Override public String role() { return "Generates policy-compliant corrective patches with before/after values and validation queries"; }

    @Override
    @SuppressWarnings("unchecked")
    protected void execute(AgentTask task, Work w) {
        AgentResult rca = task.upstream().get("RootCauseAnalysisAgent");
        Map<String, Object> rc = (Map<String, Object>) rca.data().get("rootCause");
        Map<String, Object> a = (Map<String, Object>) rc.getOrDefault("attributes", Map.of());
        String table = (String) a.get("patchTable");
        int conf = ((Number) rc.get("confidence")).intValue();
        DataPatch patch = null;
        String noPatchReason = null;
        if ("CLTB_ACCOUNT_MASTER".equals(table)) {
            if (a.get("alternateAccount") == null) noPatchReason = "No alternate active account for the customer - RM must arrange a settlement account (manual action)";
            else patch = settlementAccountPatch(task, a, conf);
        } else if ("CSTB_PARAM".equals(table)) {
            patch = parameterRestorePatch(task, a, conf);
        } else if ("ACTB_DAILY_LOG".equals(table)) {
            patch = entryAmountPatch(task, a, conf);
        } else {
            noPatchReason = "Root cause is not a data condition (category " + rc.get("category") + ") - restart without data change, or manual review";
        }
        w.data.put("patch", patch == null ? Optional.empty() : Optional.of(patch));
        w.data.put("noPatchReason", noPatchReason == null ? "" : noPatchReason);
        w.summary = patch == null ? "No data patch: " + noPatchReason : patch.patchId() + " - " + patch.title() + " (risk " + patch.riskLevel() + ")";
    }

    // ------------------------------------------------------------------ scenario: closed settlement account

    private DataPatch settlementAccountPatch(AgentTask task, Map<String, Object> a, int conf) {
        String loan = s(a.get("loan")), oldAc = s(a.get("closedAccount")), newAc = s(a.get("alternateAccount")), cust = s(a.get("customer"));
        BigDecimal emi = new BigDecimal(s(a.get("emiAmount")));
        BigDecimal bal = new BigDecimal(s(a.get("alternateBalance")));
        Map<String, Object> key = Maps.of("BRANCH_CODE", "000", "ACCOUNT_NUMBER", loan);
        List<ValidationCheck> pre = List.of(
                new ValidationCheck("Loan active and still pointing to closed account",
                        "Loan must be ACTIVE with DR_PROD_AC = " + oldAc,
                        "SELECT ACCOUNT_STATUS, DR_PROD_AC FROM CLTB_ACCOUNT_MASTER WHERE BRANCH_CODE='000' AND ACCOUNT_NUMBER='" + loan + "'", true,
                        () -> gateway.findByKey("CLTB_ACCOUNT_MASTER", key).map(r -> new ValidationCheck.Result(
                                "A".equals(r.get("ACCOUNT_STATUS")) && oldAc.equals(r.get("DR_PROD_AC")),
                                "ACCOUNT_STATUS=" + r.get("ACCOUNT_STATUS") + ", DR_PROD_AC=" + r.get("DR_PROD_AC"))).orElse(new ValidationCheck.Result(false, "loan not found"))),
                new ValidationCheck("Replacement account valid",
                        "New account must belong to the same customer, same currency, be open and not frozen",
                        "SELECT CUST_NO, CCY, RECORD_STAT, AC_STAT_FROZEN FROM STTM_CUST_ACCOUNT WHERE BRANCH_CODE='000' AND CUST_AC_NO='" + newAc + "'", true,
                        () -> gateway.findByKey("STTM_CUST_ACCOUNT", Map.of("BRANCH_CODE", "000", "CUST_AC_NO", newAc)).map(r -> new ValidationCheck.Result(
                                cust.equals(r.get("CUST_NO")) && Objects.equals(a.get("currency"), r.get("CCY")) && "O".equals(r.get("RECORD_STAT")) && "N".equals(r.get("AC_STAT_FROZEN")),
                                "CUST_NO=" + r.get("CUST_NO") + ", CCY=" + r.get("CCY") + ", RECORD_STAT=" + r.get("RECORD_STAT") + ", FROZEN=" + r.get("AC_STAT_FROZEN"))).orElse(new ValidationCheck.Result(false, "account not found"))),
                new ValidationCheck("Funds available for today's EMI",
                        "Available balance should cover EMI " + emi.toPlainString() + " (non-blocking: CL handles partial liquidation)",
                        "SELECT ACY_AVL_BAL FROM STTM_CUST_ACCOUNT WHERE BRANCH_CODE='000' AND CUST_AC_NO='" + newAc + "'", false,
                        () -> gateway.findByKey("STTM_CUST_ACCOUNT", Map.of("BRANCH_CODE", "000", "CUST_AC_NO", newAc)).map(r -> {
                            BigDecimal b = new BigDecimal(s(r.get("ACY_AVL_BAL")));
                            return new ValidationCheck.Result(b.compareTo(emi) >= 0, "ACY_AVL_BAL=" + b.toPlainString() + " vs EMI " + emi.toPlainString());
                        }).orElse(new ValidationCheck.Result(false, "account not found"))));
        List<ValidationCheck> post = List.of(
                new ValidationCheck("Settlement account updated", "DR_PROD_AC must now be " + newAc,
                        "SELECT DR_PROD_AC FROM CLTB_ACCOUNT_MASTER WHERE BRANCH_CODE='000' AND ACCOUNT_NUMBER='" + loan + "'", true,
                        () -> gateway.findByKey("CLTB_ACCOUNT_MASTER", key).map(r -> new ValidationCheck.Result(newAc.equals(r.get("DR_PROD_AC")),
                                "DR_PROD_AC=" + r.get("DR_PROD_AC"))).orElse(new ValidationCheck.Result(false, "loan not found"))),
                dryRun("CLBATCH", loan));
        return new DataPatch(ids.get(), task.investigation().id(), task.investigation().programId(), gateway.businessDate(),
                "Re-point loan " + loan + " settlement account " + oldAc + " -> " + newAc,
                "CLTB_ACCOUNT_MASTER", key, List.of(new DataPatch.FieldChange("DR_PROD_AC", oldAc, newAc)),
                "Loan " + loan + " still debits settlement account " + oldAc + ", which was closed on " + a.get("closedAt")
                        + " by " + a.get("closedBy") + ". CLBATCH cannot liquidate today's EMI from a closed account.",
                "Loan " + loan + " / customer " + cust,
                "Customer " + cust + " holds active " + a.get("currency") + " account " + newAc + " (available " + bal.toPlainString()
                        + "). Re-pointing DR_PROD_AC restores auto-liquidation without touching balances or accounting entries. "
                        + "Precedent: INC-2291 resolved identically after RM confirmation.",
                "CLBATCH can liquidate EMI " + emi.toPlainString() + " for " + loan + " from " + newAc + "; LDACCR, FXREVAL and downstream programs are unblocked.",
                List.of("Today's EMI " + emi.toPlainString() + " will be debited from " + newAc + " when CLBATCH resumes",
                        "Future EMIs will also debit " + newAc + " - customer intimation required per settlement-account change policy",
                        "No accounting entries are modified by this patch; liquidation entries are generated by CLBATCH itself",
                        "Branch should review why " + oldAc + " was closed while linked to an active loan (control gap)"),
                "MEDIUM",
                List.of("Changes which customer account is debited - obtain RM/customer confirmation before approval",
                        "Single row, reversible: rollback restores DR_PROD_AC = " + oldAc),
                List.of("Reopen account " + oldAc + " (not recommended: closure was authorised by branch)",
                        "Mark loan schedule for manual liquidation and skip auto-liquidation for today"),
                pre, post, name(), conf);
    }

    // ------------------------------------------------------------------ scenario: parameter drift

    private DataPatch parameterRestorePatch(AgentTask task, Map<String, Object> a, int conf) {
        String param = s(a.get("parameter")), cur = s(a.get("currentValue")), base = s(a.get("baselineValue"));
        Map<String, Object> key = Maps.of("PARAM_NAME", param);
        List<ValidationCheck> pre = List.of(
                new ValidationCheck("Parameter still holds the unapproved value", param + " must currently be '" + cur + "'",
                        "SELECT PARAM_VAL, MODIFIED_BY, MODIFIED_ON FROM CSTB_PARAM WHERE PARAM_NAME='" + param + "'", true,
                        () -> gateway.findByKey("CSTB_PARAM", key).map(r -> new ValidationCheck.Result(cur.equals(r.get("PARAM_VAL")),
                                "PARAM_VAL=" + r.get("PARAM_VAL") + " (by " + r.get("MODIFIED_BY") + ")")).orElse(new ValidationCheck.Result(false, "missing"))),
                new ValidationCheck("Baseline rate type has rates for all revaluation currencies",
                        "CYTM_RATES must hold " + base + " rates for USD, EUR and GBP against INR",
                        "SELECT CCY1, MID_RATE FROM CYTM_RATES WHERE CCY2='INR' AND RATE_TYPE='" + base + "'", true,
                        () -> {
                            List<String> missing = new ArrayList<>();
                            for (String c : List.of("USD", "EUR", "GBP"))
                                if (gateway.findByKey("CYTM_RATES", Map.of("CCY1", c, "CCY2", "INR", "RATE_TYPE", base)).isEmpty()) missing.add(c);
                            return new ValidationCheck.Result(missing.isEmpty(), missing.isEmpty() ? "Rates present for USD, EUR, GBP" : "Missing: " + missing);
                        }));
        String firstFailing = task.investigation().failedRecord();
        List<ValidationCheck> post = List.of(
                new ValidationCheck("Parameter restored to baseline", param + " must equal approved baseline '" + base + "'",
                        "SELECT PARAM_VAL FROM CSTB_PARAM WHERE PARAM_NAME='" + param + "'", true,
                        () -> gateway.findByKey("CSTB_PARAM", key).map(r -> new ValidationCheck.Result(base.equals(r.get("PARAM_VAL")),
                                "PARAM_VAL=" + r.get("PARAM_VAL"))).orElse(new ValidationCheck.Result(false, "missing"))),
                dryRun("FXREVAL", firstFailing));
        return new DataPatch(ids.get(), task.investigation().id(), task.investigation().programId(), gateway.businessDate(),
                "Restore " + param + " to approved baseline '" + base + "'", "CSTB_PARAM", key,
                List.of(new DataPatch.FieldChange("PARAM_VAL", cur, base)),
                param + " was changed to '" + cur + "' by " + a.get("changedBy") + " without a change ticket. Rate type '" + cur
                        + "' is not maintained, so FXREVAL cannot find revaluation rates.",
                "Parameter " + param,
                "The approved baseline (last signed-off EOD) is '" + base + "', for which rates exist for every revaluation currency. "
                        + "Restoring it is the documented resolution of INC-2244.",
                "FXREVAL revalues all foreign-currency balances at the standard mid rate; GLRECON and the rest of EOD can proceed.",
                List.of("Revaluation P&L for today will be booked at " + base + " rates (same as every prior day)",
                        "No accounting entries exist yet for this FXREVAL run - no reversal needed",
                        "Unauthorised change by " + a.get("changedBy") + " must be reported to IT Security / Ops Risk"),
                "LOW",
                List.of("Configuration-only change restoring a previously approved value", "Reversible by rollback"),
                List.of("Maintain rates for rate type '" + cur + "' (not recommended: rate type is not defined)"),
                pre, post, name(), conf);
    }

    // ------------------------------------------------------------------ scenario: unbalanced entry

    private DataPatch entryAmountPatch(AgentTask task, Map<String, Object> a, int conf) {
        String ref = s(a.get("trnRef"));
        Object sr = a.get("srNo");
        BigDecimal wrong = new BigDecimal(s(a.get("wrongAmount")));
        BigDecimal right = new BigDecimal(s(a.get("correctAmount")));
        Map<String, Object> key = Maps.of("TRN_REF_NO", ref, "AC_ENTRY_SR_NO", sr);
        List<ValidationCheck> pre = List.of(
                new ValidationCheck("Leg still carries the truncated amount", "LCY_AMOUNT must be " + wrong.toPlainString(),
                        "SELECT LCY_AMOUNT, USER_ID FROM ACTB_DAILY_LOG WHERE TRN_REF_NO='" + ref + "' AND AC_ENTRY_SR_NO=" + sr, true,
                        () -> gateway.findByKey("ACTB_DAILY_LOG", key).map(r -> new ValidationCheck.Result(
                                wrong.compareTo(new BigDecimal(s(r.get("LCY_AMOUNT")))) == 0, "LCY_AMOUNT=" + r.get("LCY_AMOUNT"))).orElse(new ValidationCheck.Result(false, "missing"))),
                new ValidationCheck("Correct amount equals contract principal", "TD " + a.get("contract") + " PRINCIPAL must be " + right.toPlainString(),
                        "SELECT PRINCIPAL FROM TDTB_CONTRACT_MASTER WHERE BRANCH_CODE='000' AND CONTRACT_REF_NO='" + a.get("contract") + "'", true,
                        () -> gateway.findByKey("TDTB_CONTRACT_MASTER", Map.of("BRANCH_CODE", "000", "CONTRACT_REF_NO", a.get("contract")))
                                .map(r -> new ValidationCheck.Result(right.compareTo(new BigDecimal(s(r.get("PRINCIPAL")))) == 0, "PRINCIPAL=" + r.get("PRINCIPAL")))
                                .orElse(new ValidationCheck.Result(false, "contract not found"))),
                new ValidationCheck("Opposite leg equals contract principal", "Debit leg must already be " + right.toPlainString(),
                        "SELECT SUM(LCY_AMOUNT) FROM ACTB_DAILY_LOG WHERE TRN_REF_NO='" + ref + "' AND DRCR_IND='D'", true,
                        () -> {
                            BigDecimal d = BigDecimal.ZERO;
                            for (Map<String, Object> l : gateway.query("ACTB_DAILY_LOG", Map.of("TRN_REF_NO", ref, "DRCR_IND", "D")))
                                d = d.add(new BigDecimal(s(l.get("LCY_AMOUNT"))));
                            return new ValidationCheck.Result(d.compareTo(right) == 0, "Debit total=" + d.toPlainString());
                        }));
        List<ValidationCheck> post = List.of(
                new ValidationCheck("Transaction balanced", "Sum(Dr) = Sum(Cr) for " + ref,
                        "SELECT DRCR_IND, SUM(LCY_AMOUNT) FROM ACTB_DAILY_LOG WHERE TRN_REF_NO='" + ref + "' GROUP BY DRCR_IND", true,
                        () -> {
                            BigDecimal d = BigDecimal.ZERO, c = BigDecimal.ZERO;
                            for (Map<String, Object> l : gateway.query("ACTB_DAILY_LOG", Map.of("TRN_REF_NO", ref))) {
                                BigDecimal v = new BigDecimal(s(l.get("LCY_AMOUNT")));
                                if ("D".equals(l.get("DRCR_IND"))) d = d.add(v); else c = c.add(v);
                            }
                            return new ValidationCheck.Result(d.compareTo(c) == 0, "Dr " + d.toPlainString() + " / Cr " + c.toPlainString());
                        }),
                dryRun("GLRECON", ref));
        return new DataPatch(ids.get(), task.investigation().id(), task.investigation().programId(), gateway.businessDate(),
                "Correct LCY_AMOUNT of " + ref + " leg " + sr + ": " + wrong.toPlainString() + " -> " + right.toPlainString(),
                "ACTB_DAILY_LOG", key, List.of(new DataPatch.FieldChange("LCY_AMOUNT", wrong, right)),
                "Leg " + sr + " of TD maturity " + ref + " credits " + a.get("account") + " with " + wrong.toPlainString()
                        + " instead of " + right.toPlainString() + " (entered by " + a.get("enteredBy") + "). The transaction is unbalanced by "
                        + right.subtract(wrong).toPlainString() + ", so GLRECON fails.",
                "Transaction " + ref + " / account " + a.get("account") + " / TD " + a.get("contract"),
                "TD " + a.get("contract") + " principal and the debit leg are both " + right.toPlainString()
                        + "; the credit leg is short by a factor of 10 (digit truncation). Precedent INC-2198 was corrected the same way after Finance sign-off.",
                "Transaction " + ref + " balances; GLRECON completes; customer account " + a.get("account") + " is credited the full maturity proceeds.",
                List.of("Customer " + a.get("account") + " balance increases by " + right.subtract(wrong).toPlainString() + " - matches the contractual payout",
                        "Sub-ledger and GL 210010000 tie after correction",
                        "Statement for " + a.get("account") + " will show the corrected amount; customer intimation may be required"),
                "HIGH",
                List.of("Modifies a financial amount on an authorised accounting entry - Finance controller sign-off required",
                        "Preferred alternative where available: reverse the transaction and re-post via FLEXCUBE",
                        "Reversible by rollback until EOD resumes"),
                List.of("Reverse " + ref + " and re-post the TD maturity through FLEXCUBE (cleaner audit, requires TD module re-run)",
                        "Post a balancing adjustment entry to suspense and correct next business day"),
                pre, post, name(), conf);
    }

    /** Post-check: re-runs the batch program's own record validation for the failing record (no posting). */
    private ValidationCheck dryRun(String program, String record) {
        return new ValidationCheck(program + " dry-run on failing record",
                "Executes " + program + " record validation for " + record + " without posting",
                "-- " + program + " validation routine (read-only dry-run) for " + record, true,
                () -> {
                    try {
                        engine.dryRun(program, record);
                        return new ValidationCheck.Result(true, program + " would now process " + record + " successfully");
                    } catch (EodProgramException e) {
                        return new ValidationCheck.Result(false, e.errorCode() + ": " + e.getMessage());
                    }
                });
    }
}
