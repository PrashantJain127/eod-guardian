package com.caixa.eodguardian.patch;

import com.caixa.eodguardian.audit.AuditService;
import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.security.AuthService;
import com.caixa.eodguardian.security.Permission;
import com.caixa.eodguardian.security.UserPrincipal;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maker-checker workflow for data patches.
 * <ul>
 *   <li>Maker (PATCH_SUBMIT) adopts an AI recommendation and submits it.</li>
 *   <li>Checker (PATCH_APPROVE), a different user, approves or rejects with a mandatory comment.</li>
 *   <li>Operator (PATCH_EXECUTE), different from maker and checker, executes with an idempotency key.</li>
 * </ul>
 * An AI agent can never move a patch beyond RECOMMENDED.
 */
public class PatchService {

    /** Lifecycle callbacks (implemented by the Supervisor to advance the investigation state machine). */
    public interface Listener {
        default void onSubmitted(DataPatch p, UserPrincipal u) {}
        default void onApproved(DataPatch p, UserPrincipal u) {}
        default void onRejected(DataPatch p, UserPrincipal u) {}
        default void onExecuted(DataPatch p, UserPrincipal u) {}
        default void onRolledBack(DataPatch p, UserPrincipal u) {}
        /** Return a reason to block rollback (e.g. program already resumed), or null to allow. */
        default String rollbackBlockedReason(DataPatch p) { return null; }
    }

    private final Map<String, DataPatch> patches = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeys = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final AuditService audit;
    private final PatchExecutor executor;
    private final List<Listener> listeners = new ArrayList<>();

    public PatchService(AuditService audit, PatchExecutor executor) {
        this.audit = audit;
        this.executor = executor;
    }

    public void addListener(Listener l) { listeners.add(l); }

    public String nextId() { return String.format("PATCH-%04d", seq.incrementAndGet()); }

    /** Registers an agent recommendation (status RECOMMENDED). */
    public void register(DataPatch p) { patches.put(p.patchId(), p); }

    public DataPatch get(String id) {
        DataPatch p = patches.get(id);
        if (p == null) throw GuardianException.notFound("Unknown patch " + id);
        return p;
    }

    public List<DataPatch> list() {
        List<DataPatch> l = new ArrayList<>(patches.values());
        l.sort(Comparator.comparing(DataPatch::patchId).reversed());
        return l;
    }

    public void clear() { patches.clear(); idempotencyKeys.clear(); }

    // ------------------------------------------------------------------ maker

    public DataPatch submit(String id, UserPrincipal user, String comment) {
        AuthService.require(user, Permission.PATCH_SUBMIT);
        DataPatch p = get(id);
        synchronized (p) {
            expect(p, PatchStatus.RECOMMENDED);
            if (!PatchPolicy.allPassed(p.policyChecks()))
                throw GuardianException.conflict("POLICY_VIOLATION", "Patch fails banking policy checks and cannot be submitted");
            p.submitted(user.username(), comment);
            p.status(PatchStatus.PENDING_APPROVAL, user.username(), comment);
        }
        audit.record(AuditService.event("PATCH_SUBMITTED").actor(user.username(), user.primaryRole()).patch(id)
                .investigation(p.investigationId()).program(p.programId()).ref(p.affectedEntity())
                .before(p.expectedValues()).after(p.proposedValues()).reason(comment).approval("PENDING")
                .outcome("SUBMITTED_FOR_APPROVAL").detail("table", p.table()).detail("key", p.key()).detail("contentHash", p.contentHash()));
        listeners.forEach(l -> l.onSubmitted(p, user));
        return p;
    }

    // ------------------------------------------------------------------ checker

    public DataPatch approve(String id, UserPrincipal user, String comment) {
        AuthService.require(user, Permission.PATCH_APPROVE);
        requireComment(comment, "Approval comment");
        DataPatch p = get(id);
        synchronized (p) {
            expect(p, PatchStatus.PENDING_APPROVAL);
            if (user.username().equals(p.maker()))
                throw denied(user, p, "Maker-checker violation: maker cannot approve own patch");
            for (ValidationCheck c : p.preChecks()) c.run(); // checker sees fresh pre-validation results
            p.decided(user.username(), comment, p.contentHash());
            p.status(PatchStatus.APPROVED, user.username(), comment);
        }
        audit.record(AuditService.event("PATCH_APPROVED").actor(user.username(), user.primaryRole()).patch(id)
                .investigation(p.investigationId()).program(p.programId()).ref(p.affectedEntity())
                .before(p.expectedValues()).after(p.proposedValues()).reason(comment).approval("APPROVED")
                .outcome("APPROVED").detail("maker", p.maker()).detail("approvedContentHash", p.approvedContentHash())
                .detail("preChecks", p.preChecks()));
        listeners.forEach(l -> l.onApproved(p, user));
        return p;
    }

    public DataPatch reject(String id, UserPrincipal user, String comment) {
        AuthService.require(user, Permission.PATCH_APPROVE);
        requireComment(comment, "Rejection reason");
        DataPatch p = get(id);
        synchronized (p) {
            if (p.status() != PatchStatus.PENDING_APPROVAL && p.status() != PatchStatus.APPROVED)
                throw GuardianException.conflict("INVALID_PATCH_STATE", "Patch " + id + " is " + p.status());
            if (user.username().equals(p.maker()))
                throw denied(user, p, "Maker-checker violation: maker cannot decide own patch");
            p.decided(user.username(), comment, null);
            p.status(PatchStatus.REJECTED, user.username(), comment);
        }
        audit.record(AuditService.event("PATCH_REJECTED").actor(user.username(), user.primaryRole()).patch(id)
                .investigation(p.investigationId()).program(p.programId()).ref(p.affectedEntity())
                .reason(comment).approval("REJECTED").outcome("REJECTED").detail("maker", p.maker()));
        listeners.forEach(l -> l.onRejected(p, user));
        return p;
    }

    // ------------------------------------------------------------------ operator

    public DataPatch execute(String id, UserPrincipal user, String idempotencyKey) {
        AuthService.require(user, Permission.PATCH_EXECUTE);
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw GuardianException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "An idempotency key is required to execute a patch");
        DataPatch p = get(id);
        synchronized (p) {
            String owner = idempotencyKeys.get(idempotencyKey);
            if (owner != null) {
                if (owner.equals(id) && p.status() == PatchStatus.EXECUTED) return p;   // safe replay: no second write
                throw GuardianException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key already used for " + owner);
            }
            if (p.status() == PatchStatus.EXECUTED)
                throw GuardianException.conflict("ALREADY_EXECUTED", "Patch " + id + " was already executed by " + p.executor());
            expect(p, PatchStatus.APPROVED);
            if (user.username().equals(p.maker()) || user.username().equals(p.checker()))
                throw denied(user, p, "Segregation of duties: executor must differ from maker and checker");
            if (!p.contentHash().equals(p.approvedContentHash()))
                throw denied(user, p, "Patch content changed after approval - re-approval required");
            idempotencyKeys.put(idempotencyKey, id);

            PatchExecutor.Outcome o = executor.apply(p);
            Map<String, Object> result = Maps.of("success", o.success(), "rolledBack", o.rolledBack(), "message", o.message(), "details", o.details());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> before = (List<Map<String, Object>>) o.details().get("beforeImage");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> after = (List<Map<String, Object>>) o.details().get("afterImage");
            p.executed(user.username(), idempotencyKey, result, before, after);
            p.status(o.success() ? PatchStatus.EXECUTED : PatchStatus.EXECUTION_FAILED, user.username(), o.message());
            audit.record(AuditService.event(o.success() ? "PATCH_EXECUTED" : "PATCH_EXECUTION_FAILED")
                    .actor(user.username(), user.primaryRole()).patch(id).investigation(p.investigationId())
                    .program(p.programId()).ref(p.affectedEntity()).before(before).after(after)
                    .reason("Executed under approval of " + p.checker()).approval("APPROVED by " + p.checker())
                    .outcome(o.success() ? "SUCCESS" : (o.rolledBack() ? "FAILED_ROLLED_BACK" : "FAILED_NOT_APPLIED"))
                    .detail("idempotencyKey", idempotencyKey).detail("message", o.message())
                    .detail("preChecks", p.preChecks()).detail("postChecks", p.postChecks()));
            if (!o.success()) idempotencyKeys.remove(idempotencyKey);
        }
        if (p.status() == PatchStatus.EXECUTED) listeners.forEach(l -> l.onExecuted(p, user));
        return p;
    }

    public DataPatch rollback(String id, UserPrincipal user, String reason) {
        AuthService.require(user, Permission.PATCH_ROLLBACK);
        requireComment(reason, "Rollback reason");
        DataPatch p = get(id);
        synchronized (p) {
            expect(p, PatchStatus.EXECUTED);
            for (Listener l : listeners) {
                String blocked = l.rollbackBlockedReason(p);
                if (blocked != null) throw GuardianException.conflict("ROLLBACK_BLOCKED", blocked);
            }
            PatchExecutor.Outcome o = executor.revert(p);
            if (!o.success()) {
                audit.record(AuditService.event("PATCH_ROLLBACK_FAILED").actor(user.username(), user.primaryRole()).patch(id)
                        .investigation(p.investigationId()).program(p.programId()).reason(reason).outcome("FAILED")
                        .detail("message", o.message()));
                throw GuardianException.conflict("ROLLBACK_FAILED", o.message());
            }
            p.rolledBack(user.username(), reason);
            p.status(PatchStatus.ROLLED_BACK, user.username(), reason);
            audit.record(AuditService.event("PATCH_ROLLED_BACK").actor(user.username(), user.primaryRole()).patch(id)
                    .investigation(p.investigationId()).program(p.programId()).ref(p.affectedEntity())
                    .before(o.details().get("beforeImage")).after(o.details().get("afterImage"))
                    .reason(reason).approval("APPROVED by " + p.checker()).outcome("ROLLED_BACK"));
        }
        listeners.forEach(l -> l.onRolledBack(p, user));
        return p;
    }

    // ------------------------------------------------------------------ helpers

    private GuardianException denied(UserPrincipal user, DataPatch p, String msg) {
        audit.record(AuditService.event("ACCESS_DENIED").actor(user.username(), user.primaryRole()).patch(p.patchId())
                .investigation(p.investigationId()).program(p.programId()).reason(msg).outcome("DENIED"));
        return GuardianException.forbidden(msg);
    }

    private static void expect(DataPatch p, PatchStatus s) {
        if (p.status() != s)
            throw GuardianException.conflict("INVALID_PATCH_STATE", "Patch " + p.patchId() + " is " + p.status() + ", expected " + s);
    }

    private static void requireComment(String c, String what) {
        if (c == null || c.isBlank() || c.trim().length() < 5)
            throw GuardianException.badRequest("COMMENT_REQUIRED", what + " is mandatory (min 5 characters)");
        if (c.length() > 1000) throw GuardianException.badRequest("COMMENT_TOO_LONG", what + " exceeds 1000 characters");
    }
}
