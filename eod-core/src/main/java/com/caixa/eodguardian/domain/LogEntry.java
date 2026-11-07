package com.caixa.eodguardian.domain;

import java.time.LocalDateTime;

/** A line of the (simulated) FLEXCUBE EOD batch log / debug trace. */
public record LogEntry(long seq, LocalDateTime timestamp, String programId, String level, String source, String message) {}
