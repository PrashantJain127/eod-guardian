package com.caixa.eodguardian.agent.workers;

import com.caixa.eodguardian.agent.AgentResult;
import com.caixa.eodguardian.agent.AgentTask;
import com.caixa.eodguardian.agent.Finding;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.flexcube.FlexcubeGateway;
import com.caixa.eodguardian.patch.DataPatch;
import com.caixa.eodguardian.patch.PatchPolicy;
import com.caixa.eodguardian.patch.ValidationCheck;

import java.util.*;

/**
 * Evaluator-Critic. Independently challenges the root cause and the proposed patch before
 * anything is shown to a human. It does not reuse the RCA agent's reasoning - it re-checks
 * the conclusions against explicit acceptance rules.
 */
public class CriticAgent extends BaseAgent {

    public static final int MIN_CONFIDENCE = 75;

    public CriticAgent(FlexcubeGateway gateway) { super(gateway); }

    @Override public String name() { return "CriticAgent"; }
    @Override public String role() { return "Evaluator: challenges root cause and patch against acceptance rules; triggers reflection"; }

    @Override
    @SuppressWarnings("unchecked")
    protected void execute(AgentTask task, Work w) {
        Map<String, AgentResult> up = task.upstream();
        Map<String, Object> rc = (Map<String, Object>) up.get("RootCauseAnalysisAgent").data().get("rootCause");
        List<Map<String, Object>> rules = new ArrayList<>();
        int conf = ((Number) rc.get("confidence")).intValue();
        Collection<String> families = (Collection<String>) rc.getOrDefault("evidenceFamilies", List.of());
        List<String> scoring = (List<String>) rc.getOrDefault("scoring", List.of());

        rule(rules, "CONFIDENCE_THRESHOLD", conf >= MIN_CONFIDENCE, "evidence",
                "Confidence " + conf + "% (minimum " + MIN_CONFIDENCE + "%)");
        rule(rules, "INDEPENDENT_EVIDENCE", families.size() >= 2, "evidence",
                families.size() + " independent evidence families " + families);
        boolean linked = scoring.stream().anyMatch(s -> s.contains("named in primary error")) || Boolean.TRUE.equals(rc.get("historicalPrecedent"));
        rule(rules, "LINKED_TO_FAILURE", linked, "logic", linked ? "Root-cause entity appears in the primary error or matches a precedent"
                : "Root-cause entity is not referenced by the primary error - possible correlation without causation");

        // ambiguity: another candidate almost as strong on a different entity
        Finding second = null;
        for (AgentResult r : up.values())
            for (Finding f : r.findings())
                if (f.isRootCandidate() && !Objects.equals(f.entityId(), rc.get("entityId"))
                        && (second == null || f.confidence() > second.confidence())) second = f;
        boolean ambiguous = second != null && second.confidence() >= conf - 5 && second.entityId() != null
                && !(second.entityId().startsWith(String.valueOf(rc.get("entityId"))));
        rule(rules, "NO_CONTRADICTION", !ambiguous, "logic", ambiguous ? "Competing candidate " + second.entityId() + " at "
                + second.confidence() + "%" : "No competing explanation of comparable strength");

        boolean postsAccounting = task.program() != null && task.program().def().postsAccounting();
        boolean reconRan = up.containsKey("ReconciliationAgent") && !"SKIPPED".equals(up.get("ReconciliationAgent").status());
        rule(rules, "RECONCILIATION_CROSS_CHECK", !postsAccounting || reconRan, "evidence",
                !postsAccounting ? "Program does not post accounting entries"
                        : reconRan ? "Reconciliation confirmed the books before any correction"
                        : "Program posts accounting entries but no reconciliation cross-check was performed");

        boolean evidenceGap = rules.stream().anyMatch(r -> !(Boolean) r.get("passed") && "evidence".equals(r.get("kind")));
        boolean logicGap = rules.stream().anyMatch(r -> !(Boolean) r.get("passed") && "logic".equals(r.get("kind")));
        String verdict = !evidenceGap && !logicGap ? "ACCEPTED"
                : (task.scope() < 2 && !logicGap) ? "NEEDS_MORE_EVIDENCE" : "NEEDS_HUMAN_REVIEW";
        w.data.put("verdict", verdict);
        w.data.put("rules", rules);
        w.data.put("iteration", task.scope());
        List<String> gaps = rules.stream().filter(r -> !(Boolean) r.get("passed")).map(r -> (String) r.get("detail")).toList();
        w.data.put("requestedActions", evidenceGap ? List.of("Run skipped specialist agents (reconciliation cross-check)",
                "Widen historical incident lookback to 365 days", "Re-score root cause") : List.of());
        w.summary = verdict + (gaps.isEmpty() ? " - all " + rules.size() + " acceptance rules passed" : " - " + String.join("; ", gaps));
    }

    /** Patch review: policy, freshness of current values, validations, link to root cause. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> reviewPatch(DataPatch p, Map<String, Object> rootCause, List<Map<String, Object>> policy) {
        List<Map<String, Object>> rules = new ArrayList<>();
        rule(rules, "BANKING_POLICY", PatchPolicy.allPassed(policy), "policy",
                policy.stream().filter(m -> !Boolean.TRUE.equals(m.get("passed"))).map(m -> (String) m.get("rule")).toList().isEmpty()
                        ? "All " + policy.size() + " policy rules passed" : "Policy violations present");
        boolean changes = p.changes().stream().noneMatch(c -> Objects.equals(String.valueOf(c.currentValue()), String.valueOf(c.proposedValue())));
        rule(rules, "EFFECTIVE_CHANGE", changes, "logic", changes ? "Every column actually changes" : "No-op change detected");
        int pass = 0, fail = 0;
        for (ValidationCheck c : p.preChecks()) {
            ValidationCheck.Result r = c.run();
            if (r.passed()) pass++; else if (c.blocking()) fail++;
        }
        rule(rules, "PRE_VALIDATION", fail == 0, "validation", pass + " pre-check(s) passed, " + fail + " blocking failure(s)");
        Map<String, Object> attrs = rootCause == null ? Map.of() : (Map<String, Object>) rootCause.getOrDefault("attributes", Map.of());
        boolean targetsRoot = p.table().equals(attrs.get("patchTable"));
        rule(rules, "TARGETS_ROOT_CAUSE", targetsRoot, "logic", targetsRoot ? "Corrects the record identified as root cause"
                : "Patch does not address the identified root cause");
        rule(rules, "RISK_DISCLOSED", !"HIGH".equals(p.riskLevel()) || p.toMap().get("riskNotes") != null, "policy",
                "Risk " + p.riskLevel() + " with notes and alternatives disclosed");
        boolean ok = rules.stream().allMatch(r -> (Boolean) r.get("passed"));
        return Maps.of("verdict", ok ? "FIT_FOR_HUMAN_APPROVAL" : "BLOCKED", "rules", rules, "reviewedBy", name());
    }

    private static void rule(List<Map<String, Object>> rules, String name, boolean passed, String kind, String detail) {
        rules.add(Maps.of("rule", name, "passed", passed, "kind", kind, "detail", detail));
    }
}
