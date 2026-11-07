package com.caixa.eodguardian.recovery;

import com.caixa.eodguardian.agent.Investigation;
import com.caixa.eodguardian.agent.InvestigationState;
import com.caixa.eodguardian.agent.SupervisorAgent;
import com.caixa.eodguardian.audit.AuditService;
import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.domain.ProgramStatus;
import com.caixa.eodguardian.eod.EodProgramException;
import com.caixa.eodguardian.eod.ProgramRuntime;
import com.caixa.eodguardian.eod.SimulatedEodEngine;
import com.caixa.eodguardian.metrics.MetricsRegistry;
import com.caixa.eodguardian.patch.DataPatch;
import com.caixa.eodguardian.patch.PatchService;
import com.caixa.eodguardian.patch.PatchStatus;
import com.caixa.eodguardian.patch.ValidationCheck;
import com.caixa.eodguardian.security.AuthService;
import com.caixa.eodguardian.security.Permission;
import com.caixa.eodguardian.security.UserPrincipal;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * I. EOD Recovery Agent - decides whether a failed program is safe to restart, enforces an
 * explicit, authorised confirmation, resumes from the checkpoint (never re-processing
 * committed records) and monitors the resumed program to completion.
 */
public class EodRecoveryAgent implements SupervisorAgent.RecoveryCoordinator {

    private final SimulatedEodEngine engine;
    private final SupervisorAgent supervisor;
    private final PatchService patches;
    private final AuditService audit;
    private final MetricsRegistry metrics;
    private final Map<String, Map<String, Object>> resumeKeys = new ConcurrentHashMap<>();
    private final Map<String, Instant> resumedAt = new ConcurrentHashMap<>();
    private final Map<String, Object> checksAtResume = new ConcurrentHashMap<>();

    public EodRecoveryAgent(SimulatedEodEngine engine, SupervisorAgent supervisor, PatchService patches, AuditService audit,
                            MetricsRegistry metrics) {
        this.engine = engine;
        this.supervisor = supervisor;
        this.patches = patches;
        this.audit = audit;
        this.metrics = metrics;
        supervisor.recovery(this);
    }

    @Override
    public Map<String, Object> assess(String programId) {
        long t0 = System.nanoTime();
        ProgramRuntime p = engine.program(programId);
        List<Map<String, Object>> checks = new ArrayList<>();
        Investigation inv = supervisor.latestFor(p.id());

        check(checks, "PROGRAM_FAILED", p.status().isTerminalFailure(), "Program status " + p.status()
                + (p.status().isTerminalFailure() ? " (resumable)" : " - only FAILED/ABORTED programs can be resumed"));
        boolean invOk = inv != null && EnumSet.of(InvestigationState.PATCH_EXECUTED, InvestigationState.NO_PATCH_REQUIRED,
                InvestigationState.READY_TO_RESUME).contains(inv.state());
        check(checks, "INVESTIGATION_RESOLVED", invOk, inv == null ? "No investigation for this failure"
                : "Investigation " + inv.id() + " is " + inv.state());

        DataPatch patch = null;
        if (inv != null && !inv.patchIds().isEmpty()) patch = patches.get(inv.patchIds().get(inv.patchIds().size() - 1));
        if (patch != null) {
            boolean executed = patch.status() == PatchStatus.EXECUTED;
            check(checks, "PATCH_EXECUTED", executed, patch.patchId() + " is " + patch.status()
                    + (executed ? " (approved by " + patch.checker() + ", executed by " + patch.executor() + ")" : ""));
            if (executed) {
                List<String> failed = new ArrayList<>();
                for (ValidationCheck c : patch.postChecks()) if (!c.run().passed() && c.blocking()) failed.add(c.name());
                check(checks, "PATCH_POST_VALIDATION", failed.isEmpty(), failed.isEmpty()
                        ? patch.postChecks().size() + " post-patch validation(s) re-run and passed" : "Failing: " + failed);
            }
        }

        String rec = p.failedRecord();
        if (rec != null && p.status().isTerminalFailure()) {
            String msg;
            boolean ok;
            try {
                engine.dryRun(p.id(), rec);
                ok = true;
                msg = "Dry-run of failing record " + rec + " now succeeds";
            } catch (EodProgramException e) {
                ok = false;
                msg = "Failing record " + rec + " still fails: " + e.errorCode();
            }
            check(checks, "FAILING_RECORD_DRY_RUN", ok, msg);
        }

        List<String> notDone = p.def().dependsOn().stream().filter(d -> !engine.program(d).status().isDone()).toList();
        check(checks, "UPSTREAM_DEPENDENCIES", notDone.isEmpty(), notDone.isEmpty()
                ? "All upstream programs complete: " + p.def().dependsOn() : "Not complete: " + notDone);
        check(checks, "NO_ACTIVE_EXECUTION", !engine.isWorkerActive(p.id()), engine.isWorkerActive(p.id())
                ? "A worker is still active for " + p.id() : "No concurrent execution of " + p.id());
        check(checks, "CHECKPOINT", true, "Restart from record " + (p.processedUnits() + 1) + " of " + p.totalUnits()
                + "; " + p.processedUnits() + " committed record(s) will not be re-processed");
        long dup = engine.duplicatePostings(p.id());
        long posted = engine.postedCount(p.id());
        boolean postingsConsistent = !p.def().postsAccounting() || posted == p.processedUnits();
        check(checks, "NO_DUPLICATE_POSTINGS", dup == 0 && postingsConsistent, p.def().postsAccounting()
                ? posted + " posting(s) for " + p.processedUnits() + " committed record(s); " + dup + " duplicate(s); re-posting guarded by idempotent posting keys"
                : "Program does not post accounting entries");
        Map<String, Object> recon = supervisor.reconciliationAgent().reconcileNow();
        check(checks, "RECONCILIATION_GATE", Boolean.TRUE.equals(recon.get("balanced")), Boolean.TRUE.equals(recon.get("balanced"))
                ? recon.get("transactions") + " transactions balanced (Dr = Cr = " + recon.get("totalDebit") + ")"
                : "Unbalanced transactions: " + recon.get("unbalanced"));

        boolean ready = checks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));
        metrics.time("agent.EodRecoveryAgent.assess", System.nanoTime() - t0);
        return Maps.of("programId", p.id(), "programName", p.def().name(), "status", p.status(), "ready", ready, "checks", checks,
                "investigationId", inv == null ? null : inv.id(), "investigationState", inv == null ? null : inv.state(),
                "patchId", patch == null ? null : patch.patchId(), "patchStatus", patch == null ? null : patch.status(),
                "checkpoint", p.processedUnits(), "totalRecords", p.totalUnits(), "reconciliation", recon,
                "requiredConfirmation", "RESUME " + p.id(), "downstream", engine.downstreamOf(p.id()),
                "runStatus", engine.runStatus(), "resumedAt", resumedAt.get(p.id()), "checksAtResume", checksAtResume.get(p.id()));
    }

    /** Authorised, explicitly confirmed, idempotent resume. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resume(String programId, UserPrincipal user, String confirmation, String idempotencyKey) {
        AuthService.require(user, Permission.EOD_RESUME);
        ProgramRuntime p = engine.program(programId);
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw GuardianException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "An idempotency key is required to resume");
        Map<String, Object> previous = resumeKeys.get(idempotencyKey);
        if (previous != null) {
            if (programId.equalsIgnoreCase(String.valueOf(previous.get("programId")))) {
                metrics.increment("recovery.duplicateResumePrevented");
                return Maps.of("replayed", true, "result", previous);
            }
            throw GuardianException.conflict("IDEMPOTENCY_KEY_REUSED", "Key already used to resume " + previous.get("programId"));
        }
        if (!("RESUME " + p.id()).equals(confirmation == null ? null : confirmation.trim()))
            throw GuardianException.badRequest("CONFIRMATION_REQUIRED", "Type exactly 'RESUME " + p.id() + "' to confirm");
        synchronized (this) {
            Map<String, Object> a = assess(programId);
            if (!Boolean.TRUE.equals(a.get("ready"))) {
                audit.record(AuditService.event("EOD_RESUME_BLOCKED").actor(user.username(), user.primaryRole()).program(p.id())
                        .reason("Recovery gates not satisfied").outcome("BLOCKED").detail("checks", a.get("checks")));
                metrics.increment("recovery.blocked");
                throw GuardianException.conflict("RECOVERY_NOT_READY", "Recovery gates failed: " +
                        ((List<Map<String, Object>>) a.get("checks")).stream().filter(c -> !Boolean.TRUE.equals(c.get("passed")))
                                .map(c -> c.get("check") + " (" + c.get("detail") + ")").toList());
            }
            Investigation inv = supervisor.latestFor(p.id());
            if (inv.state() != InvestigationState.READY_TO_RESUME)
                inv.transition(InvestigationState.READY_TO_RESUME, "agent:EodRecoveryAgent", "Recovery gates passed");
            int checkpoint = p.processedUnits();
            engine.resume(p.id());
            inv.transition(InvestigationState.RESUMED, user.username(), "Resumed from checkpoint " + checkpoint);
            resumedAt.put(p.id(), Instant.now());
            checksAtResume.put(p.id(), a.get("checks"));
            Map<String, Object> result = Maps.of("programId", p.id(), "resumedBy", user.username(), "at", Instant.now(),
                    "checkpoint", checkpoint, "investigationId", inv.id(), "idempotencyKey", idempotencyKey);
            resumeKeys.put(idempotencyKey, result);
            inv.recovery(Maps.of("resumedBy", user.username(), "resumedAt", result.get("at"), "checkpoint", checkpoint, "checks", a.get("checks")));
            audit.record(AuditService.event("EOD_RESUMED").actor(user.username(), user.primaryRole()).program(p.id()).investigation(inv.id())
                    .patch((String) a.get("patchId")).ref(p.failedRecord()).reason("Confirmed: " + confirmation)
                    .approval("Patch approved by " + (a.get("patchId") == null ? "n/a" : patches.get((String) a.get("patchId")).checker()))
                    .outcome("RESUMED").detail("checkpoint", checkpoint).detail("idempotencyKey", idempotencyKey).detail("checks", a.get("checks")));
            metrics.increment("recovery.resumed");
            return Maps.of("replayed", false, "result", result);
        }
    }

    /** Called by the Monitoring Agent when a resumed program completes or fails again. */
    public void onResumedProgramFinished(ProgramRuntime p) {
        Investigation inv = supervisor.latestFor(p.id());
        if (inv == null || inv.state() != InvestigationState.RESUMED) return;
        if (p.status() == ProgramStatus.COMPLETED) {
            inv.transition(InvestigationState.CLOSED, "agent:EodRecoveryAgent", "Resumed program completed successfully");
            metrics.increment("recovery.succeeded");
            audit.record(AuditService.event("PROGRAM_RECOVERED").agent("EodRecoveryAgent").program(p.id()).investigation(inv.id())
                    .outcome("COMPLETED").detail("attempts", p.attempts()).detail("recordsProcessed", p.totalUnits())
                    .detail("duplicatePostings", engine.duplicatePostings(p.id())));
        } else if (p.status().isTerminalFailure()) {
            metrics.increment("recovery.failedAgain");
            inv.transition(InvestigationState.DETECTED, "agent:EodRecoveryAgent", "Resumed program failed again: " + p.errorCode());
        }
    }

    private static void check(List<Map<String, Object>> l, String name, boolean passed, String detail) {
        l.add(Maps.of("check", name, "passed", passed, "detail", detail));
    }
}
