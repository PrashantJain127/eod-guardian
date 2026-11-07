package com.caixa.eodguardian.llm;

import java.util.List;
import java.util.Map;

/**
 * Deterministic provider used when no API key is configured (and as the fallback when the
 * remote provider fails). It only rephrases the structured evidence it is given, so its output
 * is always grounded - it never invents facts.
 */
public class MockLlmProvider implements LlmProvider {

    @Override public String name() { return "mock"; }

    @Override public String model() { return "rule-based-summariser-v1"; }

    @Override
    public LlmResponse complete(LlmRequest req) {
        long t0 = System.nanoTime();
        String text = switch (req.purpose()) {
            case "investigation-summary" -> investigationSummary(req.facts());
            default -> "No narrative available for purpose " + req.purpose();
        };
        int in = estimateTokens(req.systemPrompt()) + estimateTokens(req.userPrompt());
        int out = estimateTokens(text);
        return new LlmResponse(text, in, out, (System.nanoTime() - t0) / 1_000_000, name(), model(), false, null);
    }

    @SuppressWarnings("unchecked")
    private static String investigationSummary(Map<String, Object> f) {
        StringBuilder sb = new StringBuilder();
        sb.append("**What happened.** ").append(f.get("programId")).append(" (").append(f.get("programName")).append(") ")
                .append(String.valueOf(f.get("status")).toLowerCase()).append(" with ").append(f.get("errorCode"))
                .append(" while processing record ").append(f.get("failedRecord")).append(".\n\n");
        sb.append("**Root cause (").append(f.get("confidence")).append("% confidence).** ").append(f.get("rootCause")).append("\n\n");
        Object secondary = f.get("secondaryErrors");
        if (secondary instanceof List<?> l && !l.isEmpty())
            sb.append("**Secondary / cascading errors (not the cause).** ").append(String.join("; ", (List<String>) l)).append("\n\n");
        Object ev = f.get("evidence");
        if (ev instanceof List<?> l && !l.isEmpty()) {
            sb.append("**Evidence.**\n");
            for (Object o : l) sb.append("- ").append(o).append('\n');
            sb.append('\n');
        }
        if (f.get("historicalMatch") != null)
            sb.append("**Precedent.** ").append(f.get("historicalMatch")).append("\n\n");
        if (f.get("patchSummary") != null)
            sb.append("**Recommended correction.** ").append(f.get("patchSummary")).append(" Risk: ").append(f.get("patchRisk"))
                    .append(". Requires maker-checker approval before execution.\n\n");
        Object downstream = f.get("downstream");
        if (downstream instanceof List<?> l && !l.isEmpty())
            sb.append("**Downstream impact.** Waiting on this program: ").append(String.join(", ", (List<String>) l)).append(".");
        return sb.toString().trim();
    }

    static int estimateTokens(String s) { return s == null ? 0 : Math.max(1, s.length() / 4); }
}
