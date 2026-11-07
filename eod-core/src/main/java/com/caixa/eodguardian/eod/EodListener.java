package com.caixa.eodguardian.eod;

import com.caixa.eodguardian.domain.ProgramStatus;

/** Callback for EOD status transitions (used by the Monitoring Agent and the audit trail). */
public interface EodListener {
    void onProgramStatus(ProgramRuntime program, ProgramStatus from, ProgramStatus to);

    default void onRunStatus(String runId, String status) {}
}
