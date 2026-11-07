package com.caixa.eodguardian.flexcube;

import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.domain.HistoricalIncident;
import com.caixa.eodguardian.domain.ProgramRunHistory;
import com.caixa.eodguardian.domain.ProgramStatus;
import com.caixa.eodguardian.domain.Scenario;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Random;

/** Realistic, deterministic sample data for one FLEXCUBE branch, shaped per demonstration scenario. */
public final class SimulatedFlexcubeData {
    private SimulatedFlexcubeData() {}

    // Well-known records used by the failure scenarios
    public static final String EXC_LOAN = "000TLCN240117001";
    public static final String EXC_CLOSED_ACCOUNT = "0010045678";
    public static final String EXC_ALT_ACCOUNT = "0010045679";
    public static final String EXC_CUSTOMER = "000412";
    public static final String TD_MATURITY_REF = "000TDMT263030001";
    public static final BigDecimal TD_MATURITY_AMOUNT = new BigDecimal("2500000.00");
    public static final BigDecimal TD_WRONG_AMOUNT = new BigDecimal("250000.00");

    static final String[] NAMES = {"Mehta Industries Pvt Ltd", "Sharma Textiles", "Iyer Agro Exports", "Kapoor Logistics",
            "Nair Pharma Ltd", "Reddy Constructions", "Banerjee Foods", "Desai Auto Components", "Joshi Retail LLP",
            "Pillai Marine Products", "Gupta Steel Traders", "Rao Software Services", "Khan Leather Works",
            "Verma Hospitality", "Bose Electricals", "Patel Chemicals", "Singh Transport Co", "Das Handlooms",
            "Menon Spices", "Agarwal Jewellers"};

    static void seed(MockFlexcubeGateway g, Scenario scenario, LocalDate today) {
        Random r = new Random(20261030L);
        LocalDateTime dayStart = today.atTime(9, 0);

        g.defineTable("STTM_BRANCH", List.of("BRANCH_CODE"),
                List.of("BRANCH_CODE", "BRANCH_NAME", "TODAY", "PREV_WORKING_DAY", "NEXT_WORKING_DAY", "END_OF_INPUT"));
        g.insert("STTM_BRANCH", Maps.of("BRANCH_CODE", "000", "BRANCH_NAME", "Head Office - Mumbai", "TODAY", today.toString(),
                "PREV_WORKING_DAY", today.minusDays(1).toString(), "NEXT_WORKING_DAY", today.plusDays(3).toString(), "END_OF_INPUT", "T"));

        g.defineTable("STTM_CUSTOMER", List.of("CUSTOMER_NO"),
                List.of("CUSTOMER_NO", "CUSTOMER_NAME1", "CUSTOMER_TYPE", "RECORD_STAT", "FROZEN"));
        g.defineTable("STTM_CUST_ACCOUNT", List.of("BRANCH_CODE", "CUST_AC_NO"),
                List.of("BRANCH_CODE", "CUST_AC_NO", "CUST_NO", "CCY", "ACCOUNT_CLASS", "RECORD_STAT", "AC_STAT_FROZEN",
                        "AC_STAT_DORMANT", "ACY_CURR_BALANCE", "ACY_AVL_BAL", "AC_OPEN_DATE", "MAKER_ID", "CHECKER_ID", "CHECKER_DT_STAMP"));
        g.defineTable("CLTB_ACCOUNT_MASTER", List.of("BRANCH_CODE", "ACCOUNT_NUMBER"),
                List.of("BRANCH_CODE", "ACCOUNT_NUMBER", "CUSTOMER_ID", "PRODUCT_CODE", "CURRENCY", "AMOUNT_FINANCED",
                        "ACCOUNT_STATUS", "USER_DEFINED_STATUS", "DR_PROD_AC", "MATURITY_DATE", "NEXT_DUE_DATE", "EMI_AMOUNT"));
        g.defineTable("TDTB_CONTRACT_MASTER", List.of("BRANCH_CODE", "CONTRACT_REF_NO"),
                List.of("BRANCH_CODE", "CONTRACT_REF_NO", "CUST_AC_NO", "CUSTOMER_NO", "PRINCIPAL", "INTEREST_RATE",
                        "MATURITY_DATE", "CONTRACT_STATUS", "PAYOUT_AC"));
        g.defineTable("CSTB_PARAM", List.of("PARAM_NAME"),
                List.of("PARAM_NAME", "PARAM_VAL", "DESCRIPTION", "MODIFIED_BY", "MODIFIED_ON", "CHANGE_TICKET"));
        g.defineTable("CYTM_RATE_TYPE", List.of("RATE_TYPE"), List.of("RATE_TYPE", "DESCRIPTION", "RECORD_STAT"));
        g.defineTable("CYTM_RATES", List.of("CCY1", "CCY2", "RATE_TYPE"),
                List.of("CCY1", "CCY2", "RATE_TYPE", "MID_RATE", "BUY_RATE", "SALE_RATE", "RATE_DATE"));
        g.defineTable("ACTB_DAILY_LOG", List.of("TRN_REF_NO", "AC_ENTRY_SR_NO"),
                List.of("TRN_REF_NO", "AC_ENTRY_SR_NO", "AC_BRANCH", "AC_NO", "AC_CCY", "DRCR_IND", "LCY_AMOUNT",
                        "TRN_CODE", "MODULE", "EVENT", "TRN_DT", "AUTH_STAT", "USER_ID"));
        g.defineTable("GLTM_GLMASTER", List.of("GL_CODE"), List.of("GL_CODE", "GL_DESC", "CATEGORY", "LEAF"));

        // ---------------------------------------------------------------- GLs
        g.insert("GLTM_GLMASTER", Maps.of("GL_CODE", "210010000", "GL_DESC", "Term Deposits - Resident", "CATEGORY", "LIABILITY", "LEAF", "Y"));
        g.insert("GLTM_GLMASTER", Maps.of("GL_CODE", "110020000", "GL_DESC", "Term Loans - Corporate", "CATEGORY", "ASSET", "LEAF", "Y"));
        g.insert("GLTM_GLMASTER", Maps.of("GL_CODE", "410010000", "GL_DESC", "Interest Income - Loans", "CATEGORY", "INCOME", "LEAF", "Y"));
        g.insert("GLTM_GLMASTER", Maps.of("GL_CODE", "510010000", "GL_DESC", "Interest Expense - Deposits", "CATEGORY", "EXPENSE", "LEAF", "Y"));
        g.insert("GLTM_GLMASTER", Maps.of("GL_CODE", "290090000", "GL_DESC", "FX Revaluation Suspense", "CATEGORY", "LIABILITY", "LEAF", "Y"));

        // ---------------------------------------------------------------- customers & CASA accounts
        String[] ccys = {"INR", "INR", "INR", "INR", "USD", "INR", "EUR", "INR", "GBP", "INR"};
        for (int i = 0; i < NAMES.length; i++) {
            String cust = String.format("%06d", 412 + i);
            g.insert("STTM_CUSTOMER", Maps.of("CUSTOMER_NO", cust, "CUSTOMER_NAME1", NAMES[i], "CUSTOMER_TYPE", "C",
                    "RECORD_STAT", "O", "FROZEN", "N"));
            int perCust = i == 0 ? 2 : 1 + (i % 2);
            for (int a = 0; a < perCust; a++) {
                String acNo = i == 0 ? (a == 0 ? EXC_CLOSED_ACCOUNT : EXC_ALT_ACCOUNT)
                        : String.format("00100%05d", 45700 + i * 10 + a);
                String ccy = i == 0 ? "INR" : ccys[(i + a) % ccys.length];
                BigDecimal bal = money(r, 50_000, 9_500_000);
                g.insert("STTM_CUST_ACCOUNT", Maps.of("BRANCH_CODE", "000", "CUST_AC_NO", acNo, "CUST_NO", cust, "CCY", ccy,
                        "ACCOUNT_CLASS", ccy.equals("INR") ? "CACORP" : "EEFCCA", "RECORD_STAT", "O", "AC_STAT_FROZEN", "N",
                        "AC_STAT_DORMANT", "N", "ACY_CURR_BALANCE", bal, "ACY_AVL_BAL", bal,
                        "AC_OPEN_DATE", today.minusDays(400 + r.nextInt(2000)).toString(),
                        "MAKER_ID", "ops.maker2", "CHECKER_ID", "ops.checker1", "CHECKER_DT_STAMP", today.minusDays(30 + r.nextInt(300)).atTime(11, 5).toString()));
            }
        }

        // ---------------------------------------------------------------- loans (CL module)
        for (int i = 0; i < 18; i++) {
            String cust = String.format("%06d", 412 + i);
            String loan = i == 0 ? EXC_LOAN : String.format("000TLCN24%04d001", 100 + i * 2);
            String settle = i == 0 ? EXC_CLOSED_ACCOUNT : String.format("00100%05d", 45700 + i * 10);
            BigDecimal fin = money(r, 1_000_000, 50_000_000);
            g.insert("CLTB_ACCOUNT_MASTER", Maps.of("BRANCH_CODE", "000", "ACCOUNT_NUMBER", loan, "CUSTOMER_ID", cust,
                    "PRODUCT_CODE", i % 3 == 0 ? "TLCN" : "TLWC", "CURRENCY", "INR", "AMOUNT_FINANCED", fin,
                    "ACCOUNT_STATUS", "A", "USER_DEFINED_STATUS", "NORM", "DR_PROD_AC", settle,
                    "MATURITY_DATE", today.plusYears(2 + i % 4).toString(), "NEXT_DUE_DATE", today.toString(),
                    "EMI_AMOUNT", fin.divide(new BigDecimal("48"), 2, RoundingMode.HALF_UP)));
        }

        // ---------------------------------------------------------------- term deposits
        for (int i = 0; i < 12; i++) {
            String cust = String.format("%06d", 413 + i);
            String ref = i == 0 ? "000TDRC233040001" : String.format("000TDRC2330%02d001", 40 + i);
            String ac = String.format("00100%05d", 45700 + (i + 1) * 10);
            g.insert("TDTB_CONTRACT_MASTER", Maps.of("BRANCH_CODE", "000", "CONTRACT_REF_NO", ref, "CUST_AC_NO", ac,
                    "CUSTOMER_NO", cust, "PRINCIPAL", i == 0 ? TD_MATURITY_AMOUNT : money(r, 100_000, 20_000_000),
                    "INTEREST_RATE", new BigDecimal("7.10").add(new BigDecimal(r.nextInt(60)).movePointLeft(2)),
                    "MATURITY_DATE", (i == 0 ? today : today.plusDays(10 + r.nextInt(300))).toString(),
                    "CONTRACT_STATUS", i == 0 ? "M" : "A", "PAYOUT_AC", ac));
        }

        // ---------------------------------------------------------------- parameters
        String[][] params = {
                {"REVAL_RATE_TYPE", "STANDARD", "Rate type used by FX revaluation at EOD"},
                {"EOD_PARALLEL_STREAMS", "4", "Number of parallel EOD streams"},
                {"CL_AUTO_LIQD", "Y", "Auto liquidation of loan schedules during EOD"},
                {"IC_ACCR_FREQ", "D", "IC accrual frequency"},
                {"GL_RECON_TOLERANCE", "0.00", "Permitted GL vs sub-ledger difference (LCY)"},
                {"TD_AUTO_ROLLOVER", "Y", "Auto rollover of maturing deposits"},
                {"MIS_REFRESH", "Y", "Refresh MIS tables during EOD"}};
        for (String[] p : params) {
            g.insert("CSTB_PARAM", Maps.of("PARAM_NAME", p[0], "PARAM_VAL", p[1], "DESCRIPTION", p[2],
                    "MODIFIED_BY", "sys.admin", "MODIFIED_ON", today.minusDays(90).atTime(15, 0).toString(), "CHANGE_TICKET", "CR-0941"));
            g.baseline(p[0], p[1]);
        }
        g.simulatorUpdate("CSTB_PARAM", Maps.of("PARAM_NAME", "EOD_PARALLEL_STREAMS"),
                Maps.of("MODIFIED_ON", today.minusDays(18).atTime(17, 20).toString(), "MODIFIED_BY", "sys.admin", "CHANGE_TICKET", "CR-1182"));
        g.addParamChange(Maps.of("PARAM_NAME", "EOD_PARALLEL_STREAMS", "OLD_VAL", "3", "NEW_VAL", "4",
                "CHANGED_BY", "sys.admin", "CHANGED_ON", today.minusDays(18).atTime(17, 20).toString(), "CHANGE_TICKET", "CR-1182", "AUTHORISED_BY", "it.cab"));

        g.insert("CYTM_RATE_TYPE", Maps.of("RATE_TYPE", "STANDARD", "DESCRIPTION", "Standard mid rate", "RECORD_STAT", "O"));
        g.insert("CYTM_RATE_TYPE", Maps.of("RATE_TYPE", "CASH", "DESCRIPTION", "Cash rates", "RECORD_STAT", "O"));
        g.insert("CYTM_RATE_TYPE", Maps.of("RATE_TYPE", "TT", "DESCRIPTION", "Telegraphic transfer", "RECORD_STAT", "O"));
        String[][] rates = {{"USD", "84.0625"}, {"EUR", "91.3150"}, {"GBP", "109.4475"}};
        for (String[] rt : rates) {
            for (String type : List.of("STANDARD", "CASH", "TT")) {
                BigDecimal mid = new BigDecimal(rt[1]);
                g.insert("CYTM_RATES", Maps.of("CCY1", rt[0], "CCY2", "INR", "RATE_TYPE", type, "MID_RATE", mid,
                        "BUY_RATE", mid.subtract(new BigDecimal("0.15")), "SALE_RATE", mid.add(new BigDecimal("0.15")),
                        "RATE_DATE", today.toString()));
            }
        }

        // ---------------------------------------------------------------- accounting entries for the day
        int seq = 1;
        for (int i = 0; i < 26; i++) {
            String ref = String.format("000FTRF26303%04d", seq++);
            BigDecimal amt = money(r, 5_000, 4_000_000);
            String dr = String.format("00100%05d", 45700 + (1 + r.nextInt(18)) * 10);
            String cr = String.format("00100%05d", 45700 + (1 + r.nextInt(18)) * 10);
            entry(g, ref, 1, dr, "D", amt, "FT", "INIT", today, dayStart.plusMinutes(i * 17L));
            entry(g, ref, 2, cr, "C", amt, "FT", "INIT", today, dayStart.plusMinutes(i * 17L));
        }
        // TD maturity payout posted during the day (debit TD GL, credit customer CASA)
        entry(g, TD_MATURITY_REF, 1, "210010000", "D", TD_MATURITY_AMOUNT, "TD", "MATR", today, today.atTime(10, 12));
        entry(g, TD_MATURITY_REF, 2, "0010045710", "C", TD_MATURITY_AMOUNT, "TD", "MATR", today, today.atTime(10, 12));

        // ---------------------------------------------------------------- history & incidents
        String[] programs = {"AEEOTI", "CSPENDCK", "ICACCR", "TDMATP", "CLBATCH", "LDACCR", "MMACCR", "ICLIQD",
                "FXREVAL", "GLRECON", "MISREFR", "YEPLTR", "AEEOFI", "BRDTCHG", "TDBOD"};
        for (int d = 7; d >= 1; d--) {
            LocalDate bd = today.minusDays(d);
            if (bd.getDayOfWeek().getValue() >= 6) continue;
            LocalDateTime t = bd.atTime(LocalTime.of(20, 30));
            for (String p : programs) {
                long dur = 40 + r.nextInt(200);
                boolean skipped = p.equals("YEPLTR");
                g.addHistory(new ProgramRunHistory(bd, p, skipped ? ProgramStatus.SKIPPED : ProgramStatus.COMPLETED,
                        t, t.plusSeconds(dur), skipped ? 0 : dur, skipped ? 0 : 50 + r.nextInt(5000), null));
                t = t.plusSeconds(dur + 5);
            }
        }
        g.addIncident(new HistoricalIncident("INC-2291", LocalDate.of(2026, 9, 14), "CLBATCH", "CL-SCH-045",
                "Loan settlement account (DR_PROD_AC) closed by branch before loan closure",
                "Updated CLTB_ACCOUNT_MASTER.DR_PROD_AC to customer's active CASA after RM confirmation; resumed CLBATCH",
                "ops.checker1", 95));
        g.addIncident(new HistoricalIncident("INC-2244", LocalDate.of(2026, 8, 2), "FXREVAL", "CY-RATE-404",
                "REVAL_RATE_TYPE changed without change ticket to a rate type with no rates maintained",
                "Restored CSTB_PARAM.REVAL_RATE_TYPE to approved baseline STANDARD; resumed FXREVAL", "ops.checker2", 60));
        g.addIncident(new HistoricalIncident("INC-2198", LocalDate.of(2026, 6, 21), "GLRECON", "GL-RECON-001",
                "Manual upload truncated LCY_AMOUNT of one leg - debit/credit mismatch",
                "Corrected LCY_AMOUNT of the short leg after Finance sign-off; re-ran GLRECON", "fin.controller", 140));
        g.addIncident(new HistoricalIncident("INC-2150", LocalDate.of(2026, 5, 3), "ICACCR", "ORA-01555",
                "Snapshot too old - undo retention during long accrual run (infrastructure)",
                "Increased UNDO_RETENTION; restarted ICACCR", "dba.oncall", 45));

        // ---------------------------------------------------------------- scenario-specific deviations
        switch (scenario) {
            case ACCOUNT_EXCEPTION -> {
                g.simulatorUpdate("STTM_CUST_ACCOUNT", Maps.of("BRANCH_CODE", "000", "CUST_AC_NO", EXC_CLOSED_ACCOUNT),
                        Maps.of("RECORD_STAT", "C", "ACY_CURR_BALANCE", BigDecimal.ZERO.setScale(2), "ACY_AVL_BAL", BigDecimal.ZERO.setScale(2),
                                "MAKER_ID", "ops.teller3", "CHECKER_ID", "ops.branch.mgr", "CHECKER_DT_STAMP", today.atTime(16, 47).toString()));
                // Red herring: an approved, unrelated parameter change on the same day
                g.simulatorUpdate("CSTB_PARAM", Maps.of("PARAM_NAME", "MIS_REFRESH"),
                        Maps.of("MODIFIED_BY", "mis.admin", "MODIFIED_ON", today.atTime(14, 5).toString(), "CHANGE_TICKET", "CR-1207"));
                g.addParamChange(Maps.of("PARAM_NAME", "MIS_REFRESH", "OLD_VAL", "Y", "NEW_VAL", "Y",
                        "CHANGED_BY", "mis.admin", "CHANGED_ON", today.atTime(14, 5).toString(), "CHANGE_TICKET", "CR-1207", "AUTHORISED_BY", "it.cab"));
            }
            case PARAMETER_CHANGE -> {
                g.simulatorUpdate("CSTB_PARAM", Maps.of("PARAM_NAME", "REVAL_RATE_TYPE"),
                        Maps.of("PARAM_VAL", "SPOT_OLD", "MODIFIED_BY", "ops.user7", "MODIFIED_ON", today.atTime(18, 42).toString(), "CHANGE_TICKET", null));
                g.addParamChange(Maps.of("PARAM_NAME", "REVAL_RATE_TYPE", "OLD_VAL", "STANDARD", "NEW_VAL", "SPOT_OLD",
                        "CHANGED_BY", "ops.user7", "CHANGED_ON", today.atTime(18, 42).toString(), "CHANGE_TICKET", null, "AUTHORISED_BY", "ops.user7"));
            }
            case RECON_BREAK -> g.simulatorUpdate("ACTB_DAILY_LOG", Maps.of("TRN_REF_NO", TD_MATURITY_REF, "AC_ENTRY_SR_NO", 2),
                    Maps.of("LCY_AMOUNT", TD_WRONG_AMOUNT, "USER_ID", "upload.batch"));
            case SUCCESS -> { }
        }
    }

    private static void entry(MockFlexcubeGateway g, String ref, int sr, String ac, String drcr, BigDecimal amt,
                              String module, String event, LocalDate dt, LocalDateTime ts) {
        g.insert("ACTB_DAILY_LOG", Maps.of("TRN_REF_NO", ref, "AC_ENTRY_SR_NO", sr, "AC_BRANCH", "000", "AC_NO", ac,
                "AC_CCY", "INR", "DRCR_IND", drcr, "LCY_AMOUNT", amt, "TRN_CODE", module.equals("TD") ? "TDM" : "FTI",
                "MODULE", module, "EVENT", event, "TRN_DT", dt.toString(), "AUTH_STAT", "A",
                "USER_ID", module.equals("TD") ? "SYSTEM" : "ops.maker" + (1 + (sr % 3))));
    }

    private static BigDecimal money(Random r, int min, int max) {
        long v = min + (long) (r.nextDouble() * (max - min));
        return new BigDecimal(v).add(new BigDecimal(r.nextInt(100)).movePointLeft(2)).setScale(2, RoundingMode.HALF_UP);
    }
}
