package com.caixa.eodguardian.audit;

import com.caixa.eodguardian.common.Maps;

import java.util.*;

/**
 * J. Audit and Compliance Agent - verifies the hash chain and checks that every patch and
 * recovery carries the complete evidence trail regulators expect.
 */
public class AuditComplianceAgent {

    private static final List<String> PATCH_LIFECYCLE = List.of("PATCH_RECOMMENDED", "PATCH_SUBMITTED", "PATCH_APPROVED", "PATCH_EXECUTED");

    private final AuditService audit;

    public AuditComplianceAgent(AuditService audit) { this.audit = audit; }

    public Map<String, Object> report() {
        List<AuditRecord> all = audit.all();
        Map<String, Set<String>> byPatch = new TreeMap<>();
        Map<String, AuditRecord> executed = new HashMap<>();
        for (AuditRecord r : all) {
            if (r.patchId() == null) continue;
            byPatch.computeIfAbsent(r.patchId(), k -> new HashSet<>()).add(r.eventType());
            if ("PATCH_EXECUTED".equals(r.eventType())) executed.put(r.patchId(), r);
        }
        List<Map<String, Object>> patchChecks = new ArrayList<>();
        int complete = 0;
        for (String pid : executed.keySet()) {
            Set<String> ev = byPatch.get(pid);
            List<String> missing = PATCH_LIFECYCLE.stream().filter(e -> !ev.contains(e)).toList();
            AuditRecord ex = executed.get(pid);
            boolean snapshots = ex.before() != null && ex.after() != null;
            boolean identity = ex.actor() != null && !ex.actor().startsWith("agent:");
            boolean ok = missing.isEmpty() && snapshots && identity && ex.approval() != null;
            if (ok) complete++;
            patchChecks.add(Maps.of("patchId", pid, "complete", ok, "missingEvents", missing, "beforeAfterSnapshots", snapshots,
                    "humanExecutor", identity, "approvalCaptured", ex.approval() != null));
        }
        long agentDecisions = all.stream().filter(r -> "AI_AGENT".equals(r.actorRole())).count();
        long humanActions = all.stream().filter(r -> r.actorRole() != null && !"AI_AGENT".equals(r.actorRole()) && !"SYSTEM".equals(r.actorRole())).count();
        boolean agentWrites = all.stream().anyMatch(r -> "PATCH_EXECUTED".equals(r.eventType()) && r.actor() != null && r.actor().startsWith("agent:"));
        return Maps.of("chain", audit.verify(), "records", all.size(), "agentDecisions", agentDecisions, "humanActions", humanActions,
                "executedPatches", executed.size(), "completePatchTrails", complete,
                "auditCompletenessPct", executed.isEmpty() ? 100.0 : Math.round(complete * 1000.0 / executed.size()) / 10.0,
                "noAgentExecutedChanges", !agentWrites, "patchTrails", patchChecks);
    }
}
