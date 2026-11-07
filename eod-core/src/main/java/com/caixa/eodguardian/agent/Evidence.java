package com.caixa.eodguardian.agent;

/**
 * One piece of evidence an agent relied on. {@code source} is the evidence family
 * (LOG, DATABASE, PARAMETER, HISTORY, RECONCILIATION) used to judge independence.
 */
public record Evidence(String source, String reference, String detail) {
    public static Evidence log(String ref, String detail) { return new Evidence("LOG", ref, detail); }
    public static Evidence db(String ref, String detail) { return new Evidence("DATABASE", ref, detail); }
    public static Evidence param(String ref, String detail) { return new Evidence("PARAMETER", ref, detail); }
    public static Evidence history(String ref, String detail) { return new Evidence("HISTORY", ref, detail); }
    public static Evidence recon(String ref, String detail) { return new Evidence("RECONCILIATION", ref, detail); }

    @Override public String toString() { return "[" + source + "] " + reference + ": " + detail; }
}
