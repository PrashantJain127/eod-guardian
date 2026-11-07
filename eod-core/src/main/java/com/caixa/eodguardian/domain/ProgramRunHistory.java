package com.caixa.eodguardian.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Outcome of a program in a previous EOD run. */
public record ProgramRunHistory(LocalDate businessDate, String programId, ProgramStatus status,
                                LocalDateTime startTime, LocalDateTime endTime, long durationSeconds,
                                long recordsProcessed, String errorCode) {}
