package com.caixa.eodguardian.domain;

public enum ProgramStatus {
    PENDING, RUNNING, COMPLETED, FAILED, ABORTED, SKIPPED;

    public boolean isTerminalFailure() { return this == FAILED || this == ABORTED; }
    public boolean isDone() { return this == COMPLETED || this == SKIPPED; }
}
