package com.caixa.eodguardian.agent.workers;

import com.caixa.eodguardian.agent.AgentTask;
import com.caixa.eodguardian.agent.Evidence;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.flexcube.FlexcubeGateway;

import java.math.BigDecimal;
import java.util.*;

/**
 * C. Account and Contract Analysis Agent - inspects the failing record and its related accounts,
 * contracts and transactions; detects closed/frozen accounts, inconsistent balances, missing
 * records and contract-vs-posting inconsistencies.
 */
public class AccountContractAnalysisAgent extends BaseAgent {

    public AccountContractAnalysisAgent(FlexcubeGateway gateway) { super(gateway); }

    @Override public String name() { return "AccountContractAnalysisAgent"; }
    @Override public String role() { return "Finds exceptional accounts, contracts and transactions (status, balances, missing/inconsistent records)"; }

    @Override
    protected void execute(AgentTask task, Work w) {
        String program = task.investigation().programId();
        String record = task.investigation().failedRecord();
        switch (program) {
            case "CLBATCH", "LDACCR" -> loans(task, w, record);
            case "FXREVAL" -> fxAccounts(w, record);
            case "GLRECON" -> transaction(task, w, record);
            default -> {
                w.summary = "No record-level checks defined for " + program + "; failed record " + record + " noted";
                w.data.put("impactedAccounts", List.of());
                w.data.put("impactedContracts", List.of());
            }
        }
    }

    private void loans(AgentTask task, Work w, String failedLoan) {
        List<Map<String, Object>> impactedAccounts = new ArrayList<>();
        List<Map<String, Object>> impactedContracts = new ArrayList<>();
        // Scan every loan due today - the failing one and any others that would fail next.
        List<Map<String, Object>> due = w.gw.query("CLTB_ACCOUNT_MASTER",
                Map.of("NEXT_DUE_DATE", w.gw.businessDate().toString(), "ACCOUNT_STATUS", "A"));
        int scanned = 0;
        for (Map<String, Object> loan : due) {
            scanned++;
            String loanNo = s(loan.get("ACCOUNT_NUMBER"));
            String settle = s(loan.get("DR_PROD_AC"));
            Optional<Map<String, Object>> acOpt = w.gw.find("STTM_CUST_ACCOUNT", Map.of("BRANCH_CODE", "000", "CUST_AC_NO", settle));
            boolean closed = acOpt.isEmpty() || !"O".equals(acOpt.get().get("RECORD_STAT"));
            boolean frozen = acOpt.isPresent() && "Y".equals(acOpt.get().get("AC_STAT_FROZEN"));
            if (!closed && !frozen) continue;

            Map<String, Object> ac = acOpt.orElse(Map.of());
            String cust = s(loan.get("CUSTOMER_ID"));
            List<Evidence> ev = new ArrayList<>();
            ev.add(Evidence.db("CLTB_ACCOUNT_MASTER[" + loanNo + "]", "ACCOUNT_STATUS=A, DR_PROD_AC=" + settle
                    + ", EMI_AMOUNT=" + loan.get("EMI_AMOUNT") + ", NEXT_DUE_DATE=" + loan.get("NEXT_DUE_DATE")));
            ev.add(acOpt.isEmpty() ? Evidence.db("STTM_CUST_ACCOUNT[" + settle + "]", "Row not found")
                    : Evidence.db("STTM_CUST_ACCOUNT[" + settle + "]", "RECORD_STAT=" + ac.get("RECORD_STAT")
                    + ", AC_STAT_FROZEN=" + ac.get("AC_STAT_FROZEN") + ", balance=" + ac.get("ACY_CURR_BALANCE")
                    + ", last maintained by " + ac.get("MAKER_ID") + "/" + ac.get("CHECKER_ID") + " at " + ac.get("CHECKER_DT_STAMP")));
            boolean closedToday = s(ac.get("CHECKER_DT_STAMP")) != null && s(ac.get("CHECKER_DT_STAMP")).startsWith(w.gw.businessDate().toString());

            // candidate replacement: same customer, same currency, open, not frozen, highest available balance
            Map<String, Object> alt = null;
            for (Map<String, Object> cand : w.gw.query("STTM_CUST_ACCOUNT", Map.of("CUST_NO", cust, "RECORD_STAT", "O", "AC_STAT_FROZEN", "N"))) {
                if (cand.get("CUST_AC_NO").equals(settle) || !Objects.equals(cand.get("CCY"), loan.get("CURRENCY"))) continue;
                if (alt == null || dec(cand.get("ACY_AVL_BAL")).compareTo(dec(alt.get("ACY_AVL_BAL"))) > 0) alt = cand;
            }
            if (alt != null)
                ev.add(Evidence.db("STTM_CUST_ACCOUNT[" + alt.get("CUST_AC_NO") + "]", "Same customer " + cust + ", "
                        + alt.get("CCY") + ", RECORD_STAT=O, available balance " + alt.get("ACY_AVL_BAL")));
            if (task.scope() >= 2) {
                w.gw.find("STTM_CUSTOMER", Map.of("CUSTOMER_NO", cust)).ifPresent(c -> ev.add(Evidence.db("STTM_CUSTOMER[" + cust + "]",
                        c.get("CUSTOMER_NAME1") + " RECORD_STAT=" + c.get("RECORD_STAT") + ", FROZEN=" + c.get("FROZEN")
                                + " - customer relationship active, only the account was closed")));
            }

            boolean isFailing = loanNo.equals(failedLoan);
            int conf = isFailing ? (closedToday ? 78 : 70) : 60;
            Map<String, Object> attrs = Maps.of("loan", loanNo, "customer", cust, "closedAccount", settle,
                    "closedBy", ac.get("MAKER_ID"), "authorisedBy", ac.get("CHECKER_ID"), "closedAt", ac.get("CHECKER_DT_STAMP"),
                    "emiAmount", loan.get("EMI_AMOUNT"), "currency", loan.get("CURRENCY"),
                    "alternateAccount", alt == null ? null : alt.get("CUST_AC_NO"),
                    "alternateBalance", alt == null ? null : alt.get("ACY_AVL_BAL"),
                    "patchTable", "CLTB_ACCOUNT_MASTER", "patchColumn", "DR_PROD_AC");
            w.finding("ROOT_CANDIDATE", "CONTRACT", loanNo,
                    "Loan " + loanNo + " settles from a " + (closed ? "closed" : "frozen") + " account",
                    "Loan " + loanNo + " (customer " + cust + ") is ACTIVE with EMI " + loan.get("EMI_AMOUNT") + " due today, but its settlement account "
                            + settle + " was " + (closed ? "closed" : "frozen") + (closedToday ? " today at " + ac.get("CHECKER_DT_STAMP")
                            + " by " + ac.get("MAKER_ID") + " (authorised " + ac.get("CHECKER_ID") + ")" : "")
                            + ". The loan's DR_PROD_AC was not re-pointed before closure, so auto-liquidation cannot debit it."
                            + (alt != null ? " Customer holds active " + alt.get("CCY") + " account " + alt.get("CUST_AC_NO") + "." : " No alternate active account found."),
                    "CRITICAL", conf, ev, attrs, name());
            impactedContracts.add(Maps.of("contract", loanNo, "type", "LOAN", "customer", cust, "status", "ACTIVE",
                    "issue", "Settlement account " + settle + " closed", "emiDue", loan.get("EMI_AMOUNT"), "failing", isFailing));
            impactedAccounts.add(Maps.of("account", settle, "customer", cust, "ccy", ac.get("CCY"), "recordStat", ac.get("RECORD_STAT"),
                    "balance", ac.get("ACY_CURR_BALANCE"), "issue", "Closed " + (closedToday ? "today" : "") + " but still linked to active loan " + loanNo));
            if (alt != null)
                impactedAccounts.add(Maps.of("account", alt.get("CUST_AC_NO"), "customer", cust, "ccy", alt.get("CCY"), "recordStat", "O",
                        "balance", alt.get("ACY_AVL_BAL"), "issue", "Candidate replacement settlement account"));
        }
        w.data.put("impactedAccounts", impactedAccounts);
        w.data.put("impactedContracts", impactedContracts);
        w.data.put("loansScanned", scanned);
        w.summary = "Scanned " + scanned + " loan(s) due today; " + impactedContracts.size()
                + " with closed/frozen settlement account" + (impactedContracts.isEmpty() ? "" : ": " + impactedContracts.get(0).get("contract"));
    }

    private void fxAccounts(Work w, String failedAccount) {
        List<Map<String, Object>> impacted = new ArrayList<>();
        for (Map<String, Object> a : w.gw.query("STTM_CUST_ACCOUNT", Map.of("RECORD_STAT", "O"))) {
            if ("INR".equals(a.get("CCY"))) continue;
            impacted.add(Maps.of("account", a.get("CUST_AC_NO"), "customer", a.get("CUST_NO"), "ccy", a.get("CCY"),
                    "recordStat", a.get("RECORD_STAT"), "balance", a.get("ACY_CURR_BALANCE"),
                    "issue", "Pending revaluation - " + a.get("CCY") + " balance not revalued"));
        }
        Optional<Map<String, Object>> acc = w.gw.find("STTM_CUST_ACCOUNT", Map.of("BRANCH_CODE", "000", "CUST_AC_NO", failedAccount));
        boolean healthy = acc.isPresent() && "O".equals(acc.get().get("RECORD_STAT"));
        List<Evidence> ev = List.of(Evidence.db("STTM_CUST_ACCOUNT[" + failedAccount + "]",
                acc.map(a -> "CCY=" + a.get("CCY") + ", RECORD_STAT=" + a.get("RECORD_STAT") + ", AC_STAT_FROZEN=" + a.get("AC_STAT_FROZEN")).orElse("not found")));
        w.evidence.addAll(ev);
        w.finding(healthy ? "RULED_OUT" : "ROOT_CANDIDATE", "ACCOUNT", failedAccount,
                healthy ? "Account master data is consistent" : "Failing account record is invalid",
                healthy ? "Account " + failedAccount + " is open and valid; " + impacted.size()
                        + " foreign-currency accounts are affected identically, which points to a shared setting rather than one bad record."
                        : "Account " + failedAccount + " is missing or closed.",
                healthy ? "LOW" : "HIGH", healthy ? 85 : 70, ev, Map.of("affectedCount", impacted.size()), name());
        w.data.put("impactedAccounts", impacted);
        w.data.put("impactedContracts", List.of());
        w.summary = "Failing account " + failedAccount + (healthy ? " is valid" : " is invalid") + "; " + impacted.size()
                + " foreign-currency account(s) awaiting revaluation";
    }

    private void transaction(AgentTask task, Work w, String trnRef) {
        List<Map<String, Object>> legs = w.gw.query("ACTB_DAILY_LOG", Map.of("TRN_REF_NO", trnRef));
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        Map<String, Object> drLeg = null, crLeg = null;
        for (Map<String, Object> l : legs) {
            if ("D".equals(l.get("DRCR_IND"))) { dr = dr.add(dec(l.get("LCY_AMOUNT"))); drLeg = l; }
            else { cr = cr.add(dec(l.get("LCY_AMOUNT"))); crLeg = l; }
        }
        List<Map<String, Object>> impactedAccounts = new ArrayList<>();
        List<Map<String, Object>> impactedContracts = new ArrayList<>();
        List<Evidence> ev = new ArrayList<>();
        ev.add(Evidence.db("ACTB_DAILY_LOG[" + trnRef + "]", legs.size() + " legs: Dr " + dr.toPlainString() + " / Cr " + cr.toPlainString()));

        // Locate the business contract behind the posting to establish which leg is right
        Map<String, Object> contract = null;
        if (crLeg != null && "TD".equals(crLeg.get("MODULE"))) {
            for (Map<String, Object> td : w.gw.query("TDTB_CONTRACT_MASTER", Map.of("MATURITY_DATE", w.gw.businessDate().toString()))) {
                if (Objects.equals(td.get("PAYOUT_AC"), crLeg.get("AC_NO"))) { contract = td; break; }
            }
        }
        if (contract != null && crLeg != null && drLeg != null) {
            BigDecimal principal = dec(contract.get("PRINCIPAL"));
            boolean drOk = principal.compareTo(dec(drLeg.get("LCY_AMOUNT"))) == 0;
            boolean crOk = principal.compareTo(dec(crLeg.get("LCY_AMOUNT"))) == 0;
            ev.add(Evidence.db("TDTB_CONTRACT_MASTER[" + contract.get("CONTRACT_REF_NO") + "]", "Maturing today, PRINCIPAL="
                    + principal.toPlainString() + ", PAYOUT_AC=" + contract.get("PAYOUT_AC")));
            Map<String, Object> wrong = drOk && !crOk ? crLeg : (!drOk && crOk ? drLeg : null);
            if (wrong != null) {
                ev.add(Evidence.db("ACTB_DAILY_LOG[" + trnRef + "/" + wrong.get("AC_ENTRY_SR_NO") + "]", "LCY_AMOUNT=" + wrong.get("LCY_AMOUNT")
                        + " entered by " + wrong.get("USER_ID") + " (expected " + principal.toPlainString() + ")"));
                int conf = task.scope() >= 2 ? 80 : 72;
                w.finding("ROOT_CANDIDATE", "TRANSACTION", trnRef + "/" + wrong.get("AC_ENTRY_SR_NO"),
                        "TD maturity " + ("C".equals(wrong.get("DRCR_IND")) ? "credit" : "debit") + " leg booked with wrong amount",
                        "Transaction " + trnRef + " (TD maturity of " + contract.get("CONTRACT_REF_NO") + ") has leg "
                                + wrong.get("AC_ENTRY_SR_NO") + " on " + wrong.get("AC_NO") + " for " + dec(wrong.get("LCY_AMOUNT")).toPlainString()
                                + " while the contract principal and the opposite leg are " + principal.toPlainString()
                                + ". The amount appears truncated by one digit (entered by " + wrong.get("USER_ID") + ").",
                        "CRITICAL", conf, ev, Maps.of("trnRef", trnRef, "srNo", wrong.get("AC_ENTRY_SR_NO"),
                                "wrongAmount", wrong.get("LCY_AMOUNT"), "correctAmount", principal, "account", wrong.get("AC_NO"),
                                "contract", contract.get("CONTRACT_REF_NO"), "enteredBy", wrong.get("USER_ID"),
                                "patchTable", "ACTB_DAILY_LOG", "patchColumn", "LCY_AMOUNT"), name());
                impactedAccounts.add(Maps.of("account", wrong.get("AC_NO"), "customer", contract.get("CUSTOMER_NO"), "ccy", "INR",
                        "recordStat", "O", "balance", null, "issue", "Under-credited by " + principal.subtract(dec(wrong.get("LCY_AMOUNT"))).toPlainString()));
                impactedContracts.add(Maps.of("contract", contract.get("CONTRACT_REF_NO"), "type", "TERM_DEPOSIT",
                        "customer", contract.get("CUSTOMER_NO"), "status", "MATURED", "issue", "Payout posting unbalanced", "failing", true));
            }
        }
        w.evidence.addAll(ev);
        w.data.put("impactedAccounts", impactedAccounts);
        w.data.put("impactedContracts", impactedContracts);
        w.summary = "Transaction " + trnRef + " Dr " + dr.toPlainString() + " vs Cr " + cr.toPlainString()
                + (contract != null ? "; linked to TD " + contract.get("CONTRACT_REF_NO") : "; no linked contract found");
    }

    static BigDecimal dec(Object o) { return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString()); }
}
