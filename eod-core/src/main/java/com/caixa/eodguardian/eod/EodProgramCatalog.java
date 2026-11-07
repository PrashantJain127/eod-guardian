package com.caixa.eodguardian.eod;

import java.util.List;

/** The EOD program chain of the simulated FLEXCUBE branch (sequence + dependencies). */
public final class EodProgramCatalog {
    private EodProgramCatalog() {}

    public static List<EodProgramDef> programs() {
        return List.of(
                new EodProgramDef("AEEOTI", "Mark End of Transaction Input", "AE", "EOTI", 1, List.of(),
                        "AEPKS_EOTI", 3, false, false, "Closes transaction input for the branch"),
                new EodProgramDef("CSPENDCK", "Pending Items & Unauthorised Txn Check", "CS", "EOTI", 2, List.of("AEEOTI"),
                        "CSPKS_PENDING_ITEMS", 4, false, false, "Verifies no unauthorised or pending transactions remain"),
                new EodProgramDef("ICACCR", "IC Interest Accrual (CASA)", "IC", "EOFI", 3, List.of("CSPENDCK"),
                        "ICPKS_ACCR_BATCH", 6, true, false, "Daily interest accrual on current & savings accounts"),
                new EodProgramDef("TDMATP", "TD Maturity & Rollover", "TD", "EOFI", 4, List.of("CSPENDCK"),
                        "TDPKS_MATURITY", 5, true, false, "Processes maturing term deposits and rollovers"),
                new EodProgramDef("CLBATCH", "CL Auto Liquidation Batch", "CL", "EOFI", 5, List.of("CSPENDCK"),
                        "CLPKS_BATCH", 6, true, false, "Liquidates loan schedules due today from settlement accounts"),
                new EodProgramDef("LDACCR", "Loan Interest Accrual", "CL", "EOFI", 6, List.of("CLBATCH"),
                        "CLPKS_ACCR", 5, true, false, "Accrues interest on active loan accounts"),
                new EodProgramDef("MMACCR", "Money Market Accrual", "MM", "EOFI", 7, List.of("CSPENDCK"),
                        "MMPKS_ACCR", 4, true, false, "Accrual on money market placements/borrowings"),
                new EodProgramDef("ICLIQD", "IC Interest Liquidation", "IC", "EOFI", 8, List.of("ICACCR"),
                        "ICPKS_LIQD", 4, true, false, "Liquidates IC interest due today"),
                new EodProgramDef("FXREVAL", "FX Revaluation", "CY", "EOFI", 9, List.of("ICLIQD", "TDMATP", "LDACCR", "MMACCR"),
                        "CYPKS_REVAL", 5, true, false, "Revalues foreign currency balances using REVAL_RATE_TYPE"),
                new EodProgramDef("GLRECON", "GL vs Sub-ledger Reconciliation", "GL", "EOFI", 10, List.of("FXREVAL"),
                        "GLPKS_RECON", 5, false, false, "Validates every transaction is balanced and GL ties to sub-ledgers"),
                new EodProgramDef("MISREFR", "MIS Refresh", "MI", "EOFI", 11, List.of("GLRECON"),
                        "MIPKS_REFRESH", 4, false, false, "Refreshes MIS balances"),
                new EodProgramDef("YEPLTR", "Year-end P&L Transfer", "GL", "EOFI", 12, List.of("GLRECON"),
                        "GLPKS_YEAR_END", 4, false, true, "Runs on the financial year-end (last working day of March) only"),
                new EodProgramDef("AEEOFI", "Mark End of Financial Input", "AE", "EOFI", 13, List.of("GLRECON"),
                        "AEPKS_EOFI", 3, false, false, "Closes financial input for the day"),
                new EodProgramDef("BRDTCHG", "Branch Date Change", "AE", "BOD", 14, List.of("AEEOFI", "MISREFR"),
                        "AEPKS_DATE_CHANGE", 3, false, false, "Moves the branch to the next working day"),
                new EodProgramDef("TDBOD", "TD Beginning-of-Day Batch", "TD", "BOD", 15, List.of("BRDTCHG"),
                        "TDPKS_BOD", 4, false, false, "TD BOD processing for the new business date"));
    }
}
