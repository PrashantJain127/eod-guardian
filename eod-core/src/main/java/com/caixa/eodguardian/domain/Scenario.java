package com.caixa.eodguardian.domain;

/**
 * Demonstration scenarios. Each failing scenario carries its ground truth so that root-cause
 * and patch-recommendation accuracy can be measured objectively.
 */
public enum Scenario {
    SUCCESS("Successful EOD", "All programs complete normally", null, null, null),
    ACCOUNT_EXCEPTION("Exceptional loan contract",
            "CLBATCH aborts: loan settlement (DR_PROD_AC) account was closed during the day",
            "CLBATCH", "CLTB_ACCOUNT_MASTER", "DR_PROD_AC"),
    PARAMETER_CHANGE("Unexpected parameter change",
            "FXREVAL fails: REVAL_RATE_TYPE changed to an undefined rate type",
            "FXREVAL", "CSTB_PARAM", "PARAM_VAL"),
    RECON_BREAK("Accounting entry mismatch",
            "GLRECON fails: TD maturity credit leg booked with wrong amount (debit/credit break)",
            "GLRECON", "ACTB_DAILY_LOG", "LCY_AMOUNT");

    private final String title;
    private final String description;
    private final String failingProgram;
    private final String expectedPatchTable;
    private final String expectedPatchColumn;

    Scenario(String title, String description, String failingProgram, String table, String column) {
        this.title = title;
        this.description = description;
        this.failingProgram = failingProgram;
        this.expectedPatchTable = table;
        this.expectedPatchColumn = column;
    }

    public String title() { return title; }
    public String description() { return description; }
    public String failingProgram() { return failingProgram; }
    public String expectedPatchTable() { return expectedPatchTable; }
    public String expectedPatchColumn() { return expectedPatchColumn; }
}
