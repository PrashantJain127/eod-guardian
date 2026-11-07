package com.caixa.eodguardian.eod;

import com.caixa.eodguardian.common.JsonSerializable;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.domain.ProgramRunHistory;
import com.caixa.eodguardian.domain.ProgramStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Mutable runtime state of one EOD program within the current run (thread-safe). */
public final class ProgramRuntime implements JsonSerializable {
    private final EodProgramDef def;
    private ProgramStatus status = ProgramStatus.PENDING;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int totalUnits;
    private int processedUnits;          // checkpoint: units committed so far
    private String errorCode;
    private String errorMessage;
    private String failedRecord;
    private int attempts;
    private boolean delayed;
    private long pausedMillis;
    private ProgramRunHistory lastSuccess;

    ProgramRuntime(EodProgramDef def) { this.def = def; }

    public EodProgramDef def() { return def; }
    public String id() { return def.id(); }

    public synchronized ProgramStatus status() { return status; }
    public synchronized int processedUnits() { return processedUnits; }
    public synchronized int totalUnits() { return totalUnits; }
    public synchronized String errorCode() { return errorCode; }
    public synchronized String errorMessage() { return errorMessage; }
    public synchronized String failedRecord() { return failedRecord; }
    public synchronized LocalDateTime startTime() { return startTime; }
    public synchronized LocalDateTime endTime() { return endTime; }
    public synchronized int attempts() { return attempts; }
    public synchronized boolean delayed() { return delayed; }

    synchronized void markRunning(int totalUnits, boolean resume) {
        this.status = ProgramStatus.RUNNING;
        this.totalUnits = totalUnits;
        this.attempts++;
        if (!resume) {
            this.startTime = LocalDateTime.now();
            this.processedUnits = 0;
        } else if (endTime != null) {
            pausedMillis += Duration.between(endTime, LocalDateTime.now()).toMillis();
        }
        this.endTime = null;
        this.errorCode = null;
        this.errorMessage = null;
        this.failedRecord = null;
        this.delayed = false;
    }

    synchronized void checkpoint(int processed) { this.processedUnits = processed; }

    synchronized void markCompleted() {
        status = ProgramStatus.COMPLETED;
        endTime = LocalDateTime.now();
        processedUnits = totalUnits;
    }

    synchronized void markSkipped() {
        status = ProgramStatus.SKIPPED;
        startTime = endTime = LocalDateTime.now();
    }

    synchronized void markFailed(ProgramStatus st, String code, String msg, String record) {
        status = st;
        endTime = LocalDateTime.now();
        errorCode = code;
        errorMessage = msg;
        failedRecord = record;
    }

    synchronized void markDelayed(boolean d) { this.delayed = d; }

    synchronized void lastSuccess(ProgramRunHistory h) { this.lastSuccess = h; }

    /** Active execution time in ms (excludes time spent halted awaiting recovery). */
    public synchronized long elapsedMillis() {
        if (startTime == null) return 0;
        LocalDateTime end = endTime != null ? endTime : LocalDateTime.now();
        return Math.max(0, Duration.between(startTime, end).toMillis() - pausedMillis);
    }

    public synchronized int percentComplete() {
        if (status == ProgramStatus.COMPLETED) return 100;
        if (totalUnits <= 0) return 0;
        return (int) Math.min(100, Math.round(processedUnits * 100.0 / totalUnits));
    }

    @Override
    public synchronized Map<String, Object> toMap() {
        return Maps.of(
                "programId", def.id(),
                "name", def.name(),
                "module", def.module(),
                "stage", def.stage(),
                "sequence", def.sequence(),
                "dependsOn", def.dependsOn(),
                "plsqlPackage", def.plsqlPackage(),
                "description", def.description(),
                "status", status,
                "startTime", startTime,
                "endTime", endTime,
                "elapsedMs", elapsedMillis(),
                "expectedMs", (long) (def.targetSeconds() * 1000),
                "percentComplete", percentComplete(),
                "processedUnits", processedUnits,
                "totalUnits", totalUnits,
                "errorCode", errorCode,
                "errorMessage", errorMessage,
                "failedRecord", failedRecord,
                "attempts", attempts,
                "delayed", delayed,
                "postsAccounting", def.postsAccounting(),
                "lastSuccess", lastSuccess == null ? null : Maps.of(
                        "businessDate", lastSuccess.businessDate(),
                        "durationSeconds", lastSuccess.durationSeconds(),
                        "recordsProcessed", lastSuccess.recordsProcessed(),
                        "endTime", lastSuccess.endTime()));
    }

    static List<String> deps(String... d) { return List.of(d); }
}
