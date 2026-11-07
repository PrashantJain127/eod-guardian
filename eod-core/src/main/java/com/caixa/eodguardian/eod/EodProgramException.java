package com.caixa.eodguardian.eod;

import com.caixa.eodguardian.domain.ProgramStatus;

import java.util.List;

/** Raised by a simulated batch program when a record cannot be processed. */
public class EodProgramException extends RuntimeException {
    private final String errorCode;
    private final String recordRef;
    private final ProgramStatus resultingStatus;
    private final List<String> traceLines;

    public EodProgramException(String errorCode, String message, String recordRef, ProgramStatus resultingStatus,
                               List<String> traceLines) {
        super(message);
        this.errorCode = errorCode;
        this.recordRef = recordRef;
        this.resultingStatus = resultingStatus;
        this.traceLines = traceLines;
    }

    public String errorCode() { return errorCode; }
    public String recordRef() { return recordRef; }
    public ProgramStatus resultingStatus() { return resultingStatus; }
    public List<String> traceLines() { return traceLines; }
}
