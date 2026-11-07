package com.caixa.eodguardian.agent.workers;

import com.caixa.eodguardian.agent.AgentResult;
import com.caixa.eodguardian.agent.AgentTask;
import com.caixa.eodguardian.agent.Evidence;
import com.caixa.eodguardian.agent.Finding;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.flexcube.FlexcubeGateway;

import java.util.*;

/**
 * F. Root Cause Analysis Agent - fan-in point. Correlates findings from all specialists,
 * scores each root-cause candidate on independent corroboration, separates the primary failure
 * from cascading errors, and assigns a confidence with supporting evidence.
 */
public class RootCauseAnalysisAgent extends BaseAgent {

    public RootCauseAnalysisAgent(FlexcubeGateway gateway) { super(gateway); }

    @Override public String name() { return "RootCauseAnalysisAgent"; }
    @Override public String role() { return "Consolidates specialist findings, distinguishes primary vs cascading failures, scores confidence"; }

    @Override
    @SuppressWarnings("unchecked")
    protected void execute(AgentTask task, Work w) {
        Map<String, AgentResult> up = task.upstream();
        AgentResult fi = up.get("FailureInvestigationAgent");
        String primary = fi == null ? task.investigation().errorMessage() : (String) fi.data().get("primaryError");
        List<String> secondary = fi == null ? List.of() : (List<String>) fi.data().getOrDefault("secondaryErrors", List.of());
        List<Map<String, Object>> history = fi == null ? List.of() : (List<Map<String, Object>>) fi.data().getOrDefault("historicalMatches", List.of());
        boolean regression = fi != null && Boolean.TRUE.equals(fi.data().get("regression"));
        String errorCode = task.investigation().errorCode();
        String errorText = (primary == null ? "" : primary) + " " + Objects.toString(task.investigation().errorMessage(), "");

        List<Finding> candidates = new ArrayList<>();
        List<String> ruledOut = new ArrayList<>();
        List<Finding> contributing = new ArrayList<>();
        for (AgentResult r : up.values())
            for (Finding f : r.findings()) {
                if (f.isRootCandidate()) candidates.add(f);
                else if ("RULED_OUT".equals(f.category())) ruledOut.add(f.agent() + ": " + f.title());
                else if ("CONTRIBUTING".equals(f.category())) contributing.add(f);
            }

        Finding best = null;
        int bestScore = -1;
        List<String> bestReasons = List.of();
        for (Finding f : candidates) {
            int score = f.confidence();
            List<String> reasons = new ArrayList<>();
            reasons.add("base " + f.confidence() + "% from " + f.agent());
            boolean inError = f.entityId() != null && (errorText.contains(f.entityId().split("/")[0])
                    || f.attributes().values().stream().anyMatch(v -> v != null && v.toString().length() > 5 && errorText.contains(v.toString())));
            if (inError) { score += 6; reasons.add("+6 entity named in primary error"); }
            boolean precedent = history.stream().anyMatch(h -> Objects.equals(h.get("errorCode"), errorCode) && (int) h.get("similarity") == 100);
            if (precedent) { score += 10; reasons.add("+10 identical historical incident"); }
            if (regression) { score += 3; reasons.add("+3 program healthy in prior runs (new condition)"); }
            Set<String> sources = new TreeSet<>();
            for (Evidence e : f.evidence()) sources.add(e.source());
            if (fi != null) for (Evidence e : fi.evidence()) sources.add(e.source());
            for (Finding c : contributing)
                if (f.entityId() != null && c.entityId() != null && f.entityId().startsWith(c.entityId())) {
                    score += 5;
                    reasons.add("+5 corroborated by " + c.agent());
                    for (Evidence e : c.evidence()) sources.add(e.source());
                }
            int extra = Math.max(0, sources.size() - 2);
            if (extra > 0) { score += 2 * extra; reasons.add("+" + 2 * extra + " independent evidence families " + sources); }
            score = Math.min(score, 98);
            if (score > bestScore) { bestScore = score; best = f; bestReasons = reasons; }
        }

        List<String> supporting = new ArrayList<>();
        Set<String> families = new TreeSet<>();
        if (best != null) {
            for (Evidence e : best.evidence()) { supporting.add(e.toString()); families.add(e.source()); }
            if (fi != null) for (Evidence e : fi.evidence()) { supporting.add(e.toString()); families.add(e.source()); }
            for (Finding c : contributing)
                if (best.entityId() != null && c.entityId() != null && best.entityId().startsWith(c.entityId()))
                    for (Evidence e : c.evidence()) { supporting.add(e.toString()); families.add(e.source()); }
        }
        Map<String, Object> rc = best == null
                ? Maps.of("title", "Root cause not determined", "statement", "No specialist produced a root-cause candidate for "
                        + errorCode + ". Manual investigation required.", "category", task.investigation().errorCategory(),
                "entityType", null, "entityId", null, "confidence", 30, "primaryError", primary, "secondaryErrors", secondary,
                "supportingEvidence", supporting, "evidenceFamilies", families, "ruledOut", ruledOut, "scoring", List.of(),
                "historicalPrecedent", false, "attributes", Map.of(), "sourceAgent", null)
                : Maps.of("title", best.title(), "statement", best.description(), "category", task.investigation().errorCategory(),
                "entityType", best.entityType(), "entityId", best.entityId(), "confidence", bestScore,
                "primaryError", primary, "secondaryErrors", secondary, "supportingEvidence", supporting,
                "evidenceFamilies", families, "ruledOut", ruledOut, "scoring", bestReasons,
                "historicalPrecedent", history.stream().anyMatch(h -> (int) h.get("similarity") == 100),
                "alternativeCandidates", candidates.size() - 1,
                "attributes", best.attributes(), "sourceAgent", best.agent());
        w.data.put("rootCause", rc);
        w.summary = best == null ? "No root-cause candidate" : best.title() + " (" + bestScore + "% confidence, "
                + families.size() + " evidence families, " + secondary.size() + " secondary errors discounted)";
    }
}
