package com.caixa.eodguardian.domain;

import java.time.LocalDate;

/** A previously resolved EOD incident, used for correlation by the investigation agents. */
public record HistoricalIncident(String incidentId, LocalDate businessDate, String programId, String errorCode,
                                 String rootCause, String resolution, String resolvedBy, int resolutionMinutes) {}
