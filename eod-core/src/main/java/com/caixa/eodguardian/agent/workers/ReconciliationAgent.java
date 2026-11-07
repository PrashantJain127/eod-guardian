package com.caixa.eodguardian.agent.workers;

import com.caixa.eodguardian.agent.AgentTask;
import com.caixa.eodguardian.agent.Evidence;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.eod.SimulatedEodEngine;
import com.caixa.eodguardian.flexcube.FlexcubeGateway;

import java.math.BigDecimal;
import java.util.*;

/**
 * E. Reconciliation Agent - validates that every transaction is balanced, totals tie at
 * ledger level, and no EOD posting was generated twice. Also used as the reconciliation gate
 * before an EOD program is resumed.
 */
public class ReconciliationAgent extends BaseAgent {

    private final SimulatedEodEngine engine;

    public ReconciliationAgent(FlexcubeGateway gateway, SimulatedEodEngine engine) {
        super(gateway);
        this.engine = engine;
    }

    @Override public String name() { return "ReconciliationAgent"; }
    @Override public String role() { return "Finds unbalanced/unmatched entries, Dr/Cr discrepancies, ledger breaks and duplicate postings"; }

    @Override
    protected void execute(AgentTask task, Work w) {
        Map<String, Object> r = reconcile(w.gw);
        w.data.putAll(r);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> breaks = (List<Map<String, Object>>) r.get("unbalanced");
        for (Map<String, Object> b : breaks) {
            List<Evidence> ev = List.of(Evidence.recon("ACTB_DAILY_LOG[" + b.get("trnRef") + "]",
                    "Dr " + b.get("debit") + " vs Cr " + b.get("credit") + " - difference " + b.get("difference")));
            w.evidence.addAll(ev);
            w.finding("CONTRIBUTING", "TRANSACTION", (String) b.get("trnRef"), "Unbalanced transaction " + b.get("trnRef"),
                    "Debit and credit legs of " + b.get("trnRef") + " differ by " + b.get("difference") + " INR.", "CRITICAL", 85, ev,
                    b, name());
        }
        String program = task.investigation().programId();
        long dup = engine.duplicatePostings(program);
        w.data.put("duplicatePostings", dup);
        w.evidence.add(Evidence.recon("EOD posting ledger", program + ": " + engine.postedCount(program) + " posting(s), "
                + dup + " duplicate(s)"));
        if (breaks.isEmpty())
            w.finding("RULED_OUT", "PROGRAM", program, "Books are balanced",
                    "All " + r.get("transactions") + " transactions balance (Dr = Cr = " + r.get("totalDebit") + "); no accounting break.",
                    "LOW", 85, List.copyOf(w.evidence), Map.of(), name());
        w.summary = r.get("transactions") + " transactions checked; " + breaks.size() + " unbalanced; Dr " + r.get("totalDebit")
                + " / Cr " + r.get("totalCredit") + "; " + dup + " duplicate posting(s)";
    }

    /** Day-level reconciliation of ACTB_DAILY_LOG. */
    public static Map<String, Object> reconcile(ReadOnlyGateway gw) {
        Map<String, BigDecimal[]> byRef = new TreeMap<>();
        BigDecimal td = BigDecimal.ZERO, tc = BigDecimal.ZERO;
        for (Map<String, Object> l : gw.query("ACTB_DAILY_LOG", Map.of())) {
            BigDecimal amt = new BigDecimal(String.valueOf(l.get("LCY_AMOUNT")));
            BigDecimal[] dc = byRef.computeIfAbsent(String.valueOf(l.get("TRN_REF_NO")), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if ("D".equals(l.get("DRCR_IND"))) { dc[0] = dc[0].add(amt); td = td.add(amt); }
            else { dc[1] = dc[1].add(amt); tc = tc.add(amt); }
        }
        List<Map<String, Object>> unbalanced = new ArrayList<>();
        byRef.forEach((ref, dc) -> {
            if (dc[0].compareTo(dc[1]) != 0)
                unbalanced.add(Maps.of("trnRef", ref, "debit", dc[0].toPlainString(), "credit", dc[1].toPlainString(),
                        "difference", dc[0].subtract(dc[1]).abs().toPlainString()));
        });
        return Maps.of("transactions", byRef.size(), "totalDebit", td.toPlainString(), "totalCredit", tc.toPlainString(),
                "difference", td.subtract(tc).abs().toPlainString(), "balanced", unbalanced.isEmpty(), "unbalanced", unbalanced);
    }

    /** Convenience for the recovery gate (no investigation context needed). */
    public Map<String, Object> reconcileNow() { return reconcile(new ReadOnlyGateway(gateway)); }
}
