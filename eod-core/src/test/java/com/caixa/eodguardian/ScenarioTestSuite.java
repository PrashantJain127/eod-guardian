package com.caixa.eodguardian;

import com.caixa.eodguardian.agent.Investigation;
import com.caixa.eodguardian.agent.InvestigationState;
import com.caixa.eodguardian.api.ApiFacade;
import com.caixa.eodguardian.api.ApiRequest;
import com.caixa.eodguardian.api.ApiResponse;
import com.caixa.eodguardian.audit.AuditRecord;
import com.caixa.eodguardian.audit.AuditService;
import com.caixa.eodguardian.audit.InMemoryAuditSink;
import com.caixa.eodguardian.common.GuardianConfig;
import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.common.Json;
import com.caixa.eodguardian.domain.ProgramStatus;
import com.caixa.eodguardian.domain.Scenario;
import com.caixa.eodguardian.eod.SimulatedEodEngine;
import com.caixa.eodguardian.flexcube.SimulatedFlexcubeData;
import com.caixa.eodguardian.patch.DataPatch;
import com.caixa.eodguardian.patch.PatchStatus;
import com.caixa.eodguardian.security.UserPrincipal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Dependency-free end-to-end test suite covering the five demonstration scenarios and the
 * banking controls. Run with {@code gradle :eod-core:scenarioTest} (also part of {@code check}).
 */
public final class ScenarioTestSuite {

    private static int passed, failed;
    private static final List<String> failures = new ArrayList<>();
    private static final String PW = "Guardian@2026";

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        test("Scenario 1 - successful EOD", ScenarioTestSuite::successfulEod);
        test("Scenario 2/4/5 - account exception: investigate, maker-checker patch, resume", ScenarioTestSuite::accountException);
        test("Scenario 3 - parameter change: detect drift, restore baseline, resume", ScenarioTestSuite::parameterChange);
        test("Reconciliation break: HIGH-risk entry correction and resume", ScenarioTestSuite::reconBreak);
        test("Controls - segregation of duties, rejection, rollback, idempotency", ScenarioTestSuite::controls);
        test("Controls - recovery gates block unsafe resume", ScenarioTestSuite::recoveryGates);
        test("Controls - audit chain is tamper-evident", ScenarioTestSuite::auditTamper);
        test("Controls - API authentication, CSRF and RBAC", ScenarioTestSuite::apiSecurity);
        test("Agents - parallel fan-out with simulated I/O latency", ScenarioTestSuite::parallelFanOut);
        System.out.printf("%n%d passed, %d failed in %d ms%n", passed, failed, System.currentTimeMillis() - t0);
        failures.forEach(f -> System.out.println("  FAILED: " + f));
        System.exit(failed == 0 ? 0 : 1);
    }

    interface Body { void run() throws Exception; }

    private static void test(String name, Body b) {
        long t = System.currentTimeMillis();
        try {
            b.run();
            passed++;
            System.out.printf("PASS  %-78s %6d ms%n", name, System.currentTimeMillis() - t);
        } catch (Throwable e) {
            failed++;
            failures.add(name + " -> " + e);
            System.out.printf("FAIL  %-78s %s%n", name, e);
            e.printStackTrace(System.out);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static EodGuardian guardian() { return guardian(GuardianConfig.fastTest()); }

    private static EodGuardian guardian(GuardianConfig c) { return new EodGuardian(c, new InMemoryAuditSink()); }

    private static UserPrincipal user(EodGuardian g, String name) {
        return g.auth.authenticate(g.auth.login(name, PW.toCharArray())).orElseThrow();
    }

    private static void await(String what, long timeoutMs, BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static void check(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }

    private static void expectError(int status, Runnable r, String what) {
        try {
            r.run();
        } catch (GuardianException e) {
            check(e.status() == status, what + ": expected " + status + " but got " + e.status() + " " + e.getMessage());
            return;
        }
        throw new AssertionError(what + ": expected HTTP " + status + " error but call succeeded");
    }

    /** Runs a failing scenario up to the point where an investigation has produced a patch. */
    private static Investigation failAndInvestigate(EodGuardian g, Scenario sc) throws InterruptedException {
        UserPrincipal op = user(g, "meera.operator");
        g.prepareScenario(sc, op);
        g.startEod(op);
        await(sc.failingProgram() + " to fail", 30_000, () -> g.engine.program(sc.failingProgram()).status().isTerminalFailure());
        await("EOD halted", 30_000, () -> g.engine.runStatus() == SimulatedEodEngine.RunStatus.HALTED);
        await("investigation to finish", 30_000, () -> {
            Investigation i = g.supervisor.latestFor(sc.failingProgram());
            return i != null && EnumSet.of(InvestigationState.PATCH_RECOMMENDED, InvestigationState.NEEDS_HUMAN_REVIEW,
                    InvestigationState.NO_PATCH_REQUIRED, InvestigationState.ERROR).contains(i.state());
        });
        Investigation inv = g.supervisor.latestFor(sc.failingProgram());
        await("investigation metrics", 10_000, () -> !inv.performance().isEmpty());
        check(inv.state() == InvestigationState.PATCH_RECOMMENDED, "expected PATCH_RECOMMENDED, got " + inv.state() + " " + inv.rootCause());
        return inv;
    }

    /** Maker -> checker -> operator -> resume -> EOD completes. */
    private static DataPatch approveExecuteResume(EodGuardian g, Investigation inv) throws InterruptedException {
        DataPatch p = g.patches.get(inv.patchIds().get(0));
        UserPrincipal maker = user(g, "priya.maker"), checker = user(g, "arjun.checker"), op = user(g, "meera.operator");
        g.patches.submit(p.patchId(), maker, "Reviewed AI recommendation and evidence");
        g.patches.approve(p.patchId(), checker, "Approved after verifying before/after values");
        g.patches.execute(p.patchId(), op, "exec-" + p.patchId());
        check(p.status() == PatchStatus.EXECUTED, "patch should be EXECUTED, was " + p.status() + " " + p.executionResult());
        check(inv.state() == InvestigationState.READY_TO_RESUME, "investigation should be READY_TO_RESUME, was " + inv.state());
        Map<String, Object> a = g.recovery.assess(inv.programId());
        check(Boolean.TRUE.equals(a.get("ready")), "recovery should be ready: " + a.get("checks"));
        int checkpoint = g.engine.program(inv.programId()).processedUnits();
        g.recovery.resume(inv.programId(), op, "RESUME " + inv.programId(), "resume-" + inv.id());
        await("EOD completion after resume", 40_000, () -> g.engine.runStatus() == SimulatedEodEngine.RunStatus.COMPLETED);
        await("investigation closed", 5_000, () -> inv.state() == InvestigationState.CLOSED);
        check(g.engine.duplicatePostings(inv.programId()) == 0, "no duplicate postings after resume");
        check(g.engine.program(inv.programId()).attempts() == 2, "program should have run twice");
        check(checkpoint >= 0, "checkpoint captured");
        return p;
    }

    // ------------------------------------------------------------------ scenarios

    static void successfulEod() throws Exception {
        try (EodGuardian g = guardian()) {
            UserPrincipal op = user(g, "meera.operator");
            g.prepareScenario(Scenario.SUCCESS, op);
            g.startEod(op);
            await("EOD complete", 30_000, () -> g.engine.runStatus() == SimulatedEodEngine.RunStatus.COMPLETED);
            g.engine.programs().forEach(p -> check(p.status().isDone(), p.id() + " is " + p.status()));
            check(g.engine.program("YEPLTR").status() == ProgramStatus.SKIPPED, "year-end program skipped");
            check(g.supervisor.list().isEmpty(), "no investigations on a clean run");
            check(g.audit.search(Map.of("eventType", "EOD_COMPLETED"), 5).size() == 1, "EOD_COMPLETED audited");
        }
    }

    static void accountException() throws Exception {
        try (EodGuardian g = guardian()) {
            Investigation inv = failAndInvestigate(g, Scenario.ACCOUNT_EXCEPTION);
            check(g.engine.program("CLBATCH").status() == ProgramStatus.ABORTED, "CLBATCH aborted");
            Map<String, Object> rc = inv.rootCause();
            check(SimulatedFlexcubeData.EXC_LOAN.equals(rc.get("entityId")), "root cause entity is the loan: " + rc.get("entityId"));
            check(((Number) rc.get("confidence")).intValue() >= 75, "confidence >= 75: " + rc.get("confidence"));
            check(!((List<?>) rc.get("secondaryErrors")).isEmpty(), "secondary errors separated from primary");
            check(inv.reflectionIterations() == 1, "critic requested reconciliation cross-check (reflection)");
            check(inv.toMap().get("routing").toString().contains("ReconciliationAgent"), "routing recorded skip of reconciliation");
            check(Boolean.TRUE.equals(inv.accuracy().get("rootCauseCorrect")) && Boolean.TRUE.equals(inv.accuracy().get("patchCorrect")), "accuracy vs ground truth");
            check(inv.toMap().get("parameterChanges").toString().contains("NOT RELATED"), "red-herring parameter change ruled out");
            check(inv.toMap().get("aiSummary") != null, "AI summary generated");
            DataPatch p = g.patches.get(inv.patchIds().get(0));
            check(p.table().equals("CLTB_ACCOUNT_MASTER"), "patch table");
            check(SimulatedFlexcubeData.EXC_ALT_ACCOUNT.equals(p.changes().get(0).proposedValue()), "patch re-points to the customer's active account");
            check(p.sqlPreview().contains(":old_dr_prod_ac"), "SQL preview uses binds + optimistic precondition");
            approveExecuteResume(g, inv);
            var after = g.gateway.findByKey("CLTB_ACCOUNT_MASTER", Map.of("BRANCH_CODE", "000", "ACCOUNT_NUMBER", SimulatedFlexcubeData.EXC_LOAN)).orElseThrow();
            check(SimulatedFlexcubeData.EXC_ALT_ACCOUNT.equals(after.get("DR_PROD_AC")), "data corrected in FLEXCUBE");
            Map<String, Object> comp = g.compliance.report();
            check(Boolean.TRUE.equals(((Map<?, ?>) comp.get("chain")).get("valid")), "audit chain valid");
            check(((Number) comp.get("auditCompletenessPct")).doubleValue() == 100.0, "audit completeness 100%: " + comp);
            check(Boolean.TRUE.equals(comp.get("noAgentExecutedChanges")), "no AI agent executed a change");
        }
    }

    static void parameterChange() throws Exception {
        try (EodGuardian g = guardian()) {
            Investigation inv = failAndInvestigate(g, Scenario.PARAMETER_CHANGE);
            check(g.engine.program("FXREVAL").status() == ProgramStatus.FAILED, "FXREVAL failed");
            check("PARAMETER".equals(inv.errorCategory()), "category PARAMETER: " + inv.errorCategory());
            check("REVAL_RATE_TYPE".equals(inv.rootCause().get("entityId")), "root cause is REVAL_RATE_TYPE");
            DataPatch p = g.patches.get(inv.patchIds().get(0));
            check("CSTB_PARAM".equals(p.table()) && "STANDARD".equals(p.changes().get(0).proposedValue()), "restore to baseline");
            check("LOW".equals(p.riskLevel()), "LOW risk");
            approveExecuteResume(g, inv);
            check("STANDARD".equals(g.gateway.findByKey("CSTB_PARAM", Map.of("PARAM_NAME", "REVAL_RATE_TYPE")).orElseThrow().get("PARAM_VAL")), "param restored");
        }
    }

    static void reconBreak() throws Exception {
        try (EodGuardian g = guardian()) {
            Investigation inv = failAndInvestigate(g, Scenario.RECON_BREAK);
            check("RECONCILIATION".equals(inv.errorCategory()), "category RECONCILIATION");
            check(inv.reflectionIterations() == 0, "no reflection needed - reconciliation already routed");
            DataPatch p = g.patches.get(inv.patchIds().get(0));
            check("HIGH".equals(p.riskLevel()), "financial correction is HIGH risk");
            check(new BigDecimal(p.changes().get(0).proposedValue().toString()).compareTo(SimulatedFlexcubeData.TD_MATURITY_AMOUNT) == 0, "corrects to principal");
            approveExecuteResume(g, inv);
            Map<String, Object> recon = g.supervisor.reconciliationAgent().reconcileNow();
            check(Boolean.TRUE.equals(recon.get("balanced")), "books balanced after correction");
        }
    }

    static void controls() throws Exception {
        try (EodGuardian g = guardian()) {
            Investigation inv = failAndInvestigate(g, Scenario.ACCOUNT_EXCEPTION);
            DataPatch p = g.patches.get(inv.patchIds().get(0));
            UserPrincipal maker = user(g, "priya.maker"), checker = user(g, "arjun.checker"), op = user(g, "meera.operator"),
                    inv1 = user(g, "ravi.investigator"), admin = user(g, "admin");
            expectError(403, () -> g.patches.submit(p.patchId(), inv1, "trying to submit"), "investigator cannot submit");
            expectError(409, () -> g.patches.execute(p.patchId(), op, "k1"), "cannot execute unapproved patch");
            g.patches.submit(p.patchId(), maker, "Submitting for approval");
            expectError(403, () -> g.patches.approve(p.patchId(), maker, "self approval"), "maker lacks approve permission");
            expectError(403, () -> g.patches.approve(p.patchId(), admin, "admin approve"), "admin cannot approve");
            expectError(400, () -> g.patches.approve(p.patchId(), checker, ""), "approval comment mandatory");
            // reject, then re-investigate -> new recommendation
            g.patches.reject(p.patchId(), checker, "Need RM confirmation first");
            check(p.status() == PatchStatus.REJECTED && inv.state() == InvestigationState.REJECTED, "rejected");
            check(!g.audit.search(Map.of("eventType", "PATCH_REJECTED"), 5).isEmpty(), "rejection audited");

            // fresh investigation on the same failure (manual trigger after rejection)
            Investigation inv2 = g.supervisor.investigateNow("CLBATCH", inv1);
            if (inv2 == inv) throw new AssertionError("expected a new investigation");
            check(inv2.state() == InvestigationState.PATCH_RECOMMENDED, "second investigation recommends again: " + inv2.state());
            check(inv.state() == InvestigationState.CLOSED, "superseded investigation closed: " + inv.state());
            DataPatch p2 = g.patches.get(inv2.patchIds().get(0));
            g.patches.submit(p2.patchId(), maker, "Resubmitting with RM confirmation");
            g.patches.approve(p2.patchId(), checker, "RM confirmed by email");
            expectError(403, () -> g.patches.execute(p2.patchId(), checker, "k2"), "checker lacks execute permission");
            g.patches.execute(p2.patchId(), op, "k2");
            check(p2.status() == PatchStatus.EXECUTED, "executed");
            g.patches.execute(p2.patchId(), op, "k2"); // idempotent replay - no second write
            check(g.audit.search(Map.of("eventType", "PATCH_EXECUTED", "patchId", p2.patchId()), 10).size() == 1, "replay did not re-execute");
            expectError(409, () -> g.patches.execute(p2.patchId(), op, "k3"), "second execution with new key refused");
            // rollback restores before image
            g.patches.rollback(p2.patchId(), op, "Customer asked to keep old account - rolling back");
            var row = g.gateway.findByKey("CLTB_ACCOUNT_MASTER", Map.of("BRANCH_CODE", "000", "ACCOUNT_NUMBER", SimulatedFlexcubeData.EXC_LOAN)).orElseThrow();
            check(SimulatedFlexcubeData.EXC_CLOSED_ACCOUNT.equals(row.get("DR_PROD_AC")), "rollback restored before-image");
            check(p2.status() == PatchStatus.ROLLED_BACK && inv2.state() == InvestigationState.ROLLED_BACK, "rolled back state");
        }
    }

    static void recoveryGates() throws Exception {
        try (EodGuardian g = guardian()) {
            Investigation inv = failAndInvestigate(g, Scenario.PARAMETER_CHANGE);
            UserPrincipal op = user(g, "meera.operator"), maker = user(g, "priya.maker");
            Map<String, Object> a = g.recovery.assess("FXREVAL");
            check(!Boolean.TRUE.equals(a.get("ready")), "not ready before patch");
            expectError(409, () -> g.recovery.resume("FXREVAL", op, "RESUME FXREVAL", "r1"), "resume blocked before patch");
            expectError(403, () -> g.recovery.resume("FXREVAL", maker, "RESUME FXREVAL", "r2"), "maker cannot resume");
            DataPatch p = g.patches.get(inv.patchIds().get(0));
            g.patches.submit(p.patchId(), maker, "Restore baseline");
            g.patches.approve(p.patchId(), user(g, "arjun.checker"), "Unauthorised change confirmed");
            g.patches.execute(p.patchId(), op, "e1");
            expectError(400, () -> g.recovery.resume("FXREVAL", op, "yes", "r3"), "explicit confirmation required");
            var first = g.recovery.resume("FXREVAL", op, "RESUME FXREVAL", "r4");
            var replay = g.recovery.resume("FXREVAL", op, "RESUME FXREVAL", "r4");
            check(Boolean.TRUE.equals(replay.get("replayed")) && Boolean.FALSE.equals(first.get("replayed")), "duplicate resume is a no-op");
            expectError(409, () -> g.patches.rollback(p.patchId(), op, "too late to roll back"), "rollback blocked after resume");
            await("EOD complete", 40_000, () -> g.engine.runStatus() == SimulatedEodEngine.RunStatus.COMPLETED);
        }
    }

    static void auditTamper() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        AuditService a = new AuditService(sink, () -> LocalDate.of(2026, 10, 30));
        for (int i = 0; i < 5; i++) a.record(AuditService.event("TEST").actor("u" + i, "TESTER").outcome("OK").before(Map.of("v", i)));
        check(Boolean.TRUE.equals(a.verify().get("valid")), "chain valid");
        check(Boolean.TRUE.equals(new AuditService(sink, () -> LocalDate.now()).chainValidOnLoad()), "chain valid after reload");
        AuditRecord r = sink.loadAll().get(2);
        sink.tamper(2, new AuditRecord(r.seq(), r.auditId(), r.timestamp(), r.businessDate(), r.eventType(), "mallory", r.actorRole(),
                r.programId(), r.investigationId(), r.patchId(), r.entityRefs(), r.before(), r.after(), r.reason(), r.approval(),
                r.outcome(), r.details(), r.prevHash(), r.hash()));
        AuditService reloaded = new AuditService(sink, () -> LocalDate.now());
        check(!reloaded.chainValidOnLoad(), "tampering detected");
        check("AUD-0000003".equals(reloaded.verify().get("brokenAt")), "break located at tampered record");
    }

    static void apiSecurity() throws Exception {
        try (EodGuardian g = guardian()) {
            ApiFacade api = new ApiFacade(g);
            ApiResponse r = api.handle(req("GET", "/api/dashboard", null, null, false));
            check(r.status() == 401, "dashboard requires login: " + r.status());
            ApiResponse bad = api.handle(req("POST", "/api/auth/login", "{\"username\":\"admin\",\"password\":\"wrong\"}", null, false));
            check(bad.status() == 401, "bad password rejected");
            ApiResponse login = api.handle(req("POST", "/api/auth/login", "{\"username\":\"ravi.investigator\",\"password\":\"" + PW + "\"}", null, false));
            check(login.status() == 200 && login.headers().get("Set-Cookie").contains("HttpOnly"), "login sets HttpOnly cookie");
            String token = Json.str(Json.parseObject(login.body()), "token");
            check(api.handle(req("GET", "/api/dashboard", null, token, false)).status() == 200, "dashboard ok");
            check(api.handle(req("POST", "/api/eod/start", "{}", token, false)).status() == 403, "CSRF header required");
            check(api.handle(req("POST", "/api/eod/start", "{}", token, true)).status() == 403, "investigator cannot start EOD");
            check(api.handle(req("GET", "/api/audit", null, token, false)).status() == 403, "investigator cannot read audit");
            ApiResponse inj = api.handle(req("POST", "/api/investigations", "{\"programId\":\"CLBATCH' OR 1=1--\"}", token, true));
            check(inj.status() == 400, "injection-shaped identifier rejected: " + inj.status());
            check(api.handle(req("GET", "/api/metrics", null, token, false)).status() == 200, "metrics readable");
        }
    }

    static void parallelFanOut() throws Exception {
        try (EodGuardian g = guardian(GuardianConfig.fastTest().withLatency(40))) {
            Investigation inv = failAndInvestigate(g, Scenario.RECON_BREAK);
            Map<String, Object> perf = inv.performance();
            double speedup = ((Number) perf.get("parallelSpeedup")).doubleValue();
            check(speedup > 1.5, "fan-out should run specialists concurrently (speedup " + speedup + ", " + perf + ")");
            System.out.println("      fan-out wall " + perf.get("fanOutWallMs") + " ms vs sequential " + perf.get("sumParallelAgentMs")
                    + " ms -> speedup x" + speedup + "; e2e " + perf.get("endToEndMs") + " ms");
        }
    }

    private static ApiRequest req(String method, String path, String body, String token, boolean csrf) {
        Map<String, String> h = new HashMap<>();
        if (token != null) h.put("authorization", "Bearer " + token);
        if (csrf) h.put(ApiFacade.CSRF_HEADER, ApiFacade.CSRF_VALUE);
        return new ApiRequest(method, path, Map.of(), h, body);
    }
}
