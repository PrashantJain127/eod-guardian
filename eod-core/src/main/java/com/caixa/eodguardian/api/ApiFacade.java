package com.caixa.eodguardian.api;

import com.caixa.eodguardian.EodGuardian;
import com.caixa.eodguardian.agent.Investigation;
import com.caixa.eodguardian.agent.InvestigationState;
import com.caixa.eodguardian.audit.AuditRecord;
import com.caixa.eodguardian.audit.AuditService;
import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.common.Json;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.domain.LogEntry;
import com.caixa.eodguardian.domain.Scenario;
import com.caixa.eodguardian.eod.ProgramRuntime;
import com.caixa.eodguardian.patch.DataPatch;
import com.caixa.eodguardian.patch.PatchStatus;
import com.caixa.eodguardian.security.AuthService;
import com.caixa.eodguardian.security.Permission;
import com.caixa.eodguardian.security.UserPrincipal;

import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transport-neutral REST API. The standalone JDK server and the Spring Boot controller both
 * delegate here, so behaviour, authorisation and validation are identical in both runtimes.
 */
public class ApiFacade {
    private static final Logger LOG = Logger.getLogger(ApiFacade.class.getName());
    public static final String SESSION_COOKIE = "EODG_SESSION";
    public static final String CSRF_HEADER = "x-requested-with";
    public static final String CSRF_VALUE = "EODGuardian";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    private final EodGuardian g;

    public ApiFacade(EodGuardian guardian) { this.g = guardian; }

    public EodGuardian guardian() { return g; }

    public ApiResponse handle(ApiRequest req) {
        long t0 = System.nanoTime();
        String route = req.method() + " " + normalise(req.path());
        g.metrics.markRequest();
        try {
            return dispatch(req);
        } catch (GuardianException e) {
            if (e.status() == 403) LOG.warning("Access denied on " + route + ": " + e.getMessage());
            return ApiResponse.error(e.status(), e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, "BAD_REQUEST", e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Unhandled error on " + route, e);
            return ApiResponse.error(500, "INTERNAL_ERROR", "Unexpected error - see server log (correlation " + Instant.now().toEpochMilli() + ")");
        } finally {
            g.metrics.time("api." + route, System.nanoTime() - t0);
        }
    }

    private static String normalise(String path) {
        return path.replaceAll("/(PATCH|INV)-[A-Za-z0-9\\-]+", "/{id}").replaceAll("/programs/[A-Z0-9]+", "/programs/{id}")
                .replaceAll("/recovery/[A-Z0-9]+", "/recovery/{id}");
    }

    private ApiResponse dispatch(ApiRequest r) {
        String m = r.method().toUpperCase(Locale.ROOT);
        String p = r.path();
        if (!p.startsWith("/api/")) return ApiResponse.error(404, "NOT_FOUND", "No route");

        // ---------------- public
        if (m.equals("GET") && p.equals("/api/health"))
            return ApiResponse.ok(Maps.of("status", "UP", "time", Instant.now(), "auditChainValid", g.audit.verify().get("valid")));
        if (m.equals("GET") && p.equals("/api/auth/users")) return ApiResponse.ok(g.auth.directory());
        if (m.equals("POST") && p.equals("/api/auth/login")) return login(r);

        // ---------------- CSRF guard for state-changing calls (cookie auth)
        if (!m.equals("GET") && !CSRF_VALUE.equals(r.header(CSRF_HEADER)))
            return ApiResponse.error(403, "CSRF", "Missing " + CSRF_HEADER + " header");

        UserPrincipal user = g.auth.authenticate(r.token()).orElseThrow(() -> GuardianException.unauthorized("Login required"));
        Map<String, Object> body = m.equals("GET") ? Map.of() : Json.parseObject(r.body());

        if (m.equals("POST") && p.equals("/api/auth/logout")) {
            g.auth.logout(r.token());
            return ApiResponse.ok(Map.of("ok", true)).header("Set-Cookie", SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        }
        if (m.equals("GET") && p.equals("/api/auth/me")) return ApiResponse.ok(user);

        AuthService.require(user, Permission.VIEW_DASHBOARD);

        // ---------------- dashboard & EOD
        if (m.equals("GET") && p.equals("/api/dashboard")) return ApiResponse.ok(dashboard());
        if (m.equals("GET") && p.equals("/api/eod/programs")) return ApiResponse.ok(g.engine.programs());
        if (m.equals("GET") && p.equals("/api/eod/scenarios")) {
            List<Map<String, Object>> l = new ArrayList<>();
            for (Scenario s : Scenario.values())
                l.add(Maps.of("id", s, "title", s.title(), "description", s.description(), "failingProgram", s.failingProgram()));
            return ApiResponse.ok(l);
        }
        Matcher mm;
        if (m.equals("GET") && (mm = match("/api/eod/programs/([A-Z0-9]+)/logs", p)) != null) {
            List<LogEntry> logs = g.logs.forProgram(g.engine.program(mm.group(1)).id(), 300);
            return ApiResponse.ok(logs);
        }
        if (m.equals("GET") && p.equals("/api/eod/logs")) return ApiResponse.ok(g.logs.forProgram(null, 300));
        if (m.equals("POST") && p.equals("/api/eod/prepare")) {
            g.prepareScenario(scenario(Json.str(body, "scenario")), user);
            return ApiResponse.ok(g.engine.overview());
        }
        if (m.equals("POST") && p.equals("/api/eod/start")) {
            if (body.get("scenario") != null) g.prepareScenario(scenario(Json.str(body, "scenario")), user);
            g.startEod(user);
            return ApiResponse.ok(g.engine.overview());
        }

        // ---------------- investigations
        if (m.equals("GET") && p.equals("/api/investigations")) return ApiResponse.ok(g.supervisor.list().stream().map(Investigation::summary).toList());
        if (m.equals("GET") && (mm = match("/api/investigations/(INV-[A-Za-z0-9\\-]+)", p)) != null)
            return ApiResponse.ok(g.supervisor.get(mm.group(1)));
        if (m.equals("POST") && p.equals("/api/investigations")) {
            AuthService.require(user, Permission.INVESTIGATE);
            return ApiResponse.ok(g.supervisor.investigateNow(id(Json.str(body, "programId")), user));
        }
        if (m.equals("GET") && p.equals("/api/agents")) return ApiResponse.ok(Maps.of("agents", g.supervisor.agentCatalog(),
                "stateMachine", InvestigationState.graph()));

        // ---------------- patches (maker-checker)
        if (m.equals("GET") && p.equals("/api/patches")) return ApiResponse.ok(g.patches.list());
        if (m.equals("GET") && (mm = match("/api/patches/(PATCH-\\d{4})", p)) != null) return ApiResponse.ok(g.patches.get(mm.group(1)));
        if (m.equals("POST") && (mm = match("/api/patches/(PATCH-\\d{4})/(submit|approve|reject|execute|rollback)", p)) != null) {
            String pid = mm.group(1);
            String comment = Json.str(body, "comment");
            DataPatch out = switch (mm.group(2)) {
                case "submit" -> g.patches.submit(pid, user, comment);
                case "approve" -> g.patches.approve(pid, user, comment);
                case "reject" -> g.patches.reject(pid, user, comment);
                case "execute" -> g.patches.execute(pid, user, Json.str(body, "idempotencyKey"));
                default -> g.patches.rollback(pid, user, comment);
            };
            return ApiResponse.ok(out);
        }

        // ---------------- recovery
        if (m.equals("GET") && (mm = match("/api/recovery/([A-Z0-9]+)", p)) != null) return ApiResponse.ok(g.recovery.assess(mm.group(1)));
        if (m.equals("POST") && (mm = match("/api/recovery/([A-Z0-9]+)/resume", p)) != null)
            return ApiResponse.ok(g.recovery.resume(mm.group(1), user, Json.str(body, "confirmation"), Json.str(body, "idempotencyKey")));

        // ---------------- audit & metrics
        if (m.equals("GET") && p.equals("/api/audit")) {
            AuthService.require(user, Permission.VIEW_AUDIT);
            int limit = Math.min(1000, Integer.parseInt(r.query().getOrDefault("limit", "300")));
            List<AuditRecord> rs = g.audit.search(r.query(), limit);
            return ApiResponse.ok(Maps.of("total", g.audit.size(), "returned", rs.size(), "records", rs));
        }
        if (m.equals("GET") && p.equals("/api/audit/verify")) {
            AuthService.require(user, Permission.VIEW_AUDIT);
            return ApiResponse.ok(g.compliance.report());
        }
        if (m.equals("GET") && p.equals("/api/metrics")) {
            AuthService.require(user, Permission.VIEW_DASHBOARD);
            return ApiResponse.ok(metrics());
        }
        return ApiResponse.error(404, "NOT_FOUND", "No route for " + m + " " + p);
    }

    private ApiResponse login(ApiRequest r) {
        Map<String, Object> b = Json.parseObject(r.body());
        String u = Json.str(b, "username");
        String pw = Json.str(b, "password");
        try {
            String token = g.auth.login(u, pw == null ? null : pw.toCharArray());
            UserPrincipal user = g.auth.authenticate(token).orElseThrow();
            g.audit.record(AuditService.event("LOGIN").actor(user.username(), user.primaryRole()).outcome("SUCCESS"));
            return ApiResponse.ok(Maps.of("user", user, "token", token))
                    .header("Set-Cookie", SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");
        } catch (GuardianException e) {
            g.audit.record(AuditService.event("LOGIN").actor(u == null ? "unknown" : u.replaceAll("[^A-Za-z0-9._-]", ""), "UNKNOWN")
                    .outcome("FAILED").reason(e.code()));
            throw e;
        }
    }

    // ------------------------------------------------------------------ views

    public Map<String, Object> dashboard() {
        long t0 = System.nanoTime();
        List<Map<String, Object>> invs = g.supervisor.list().stream().limit(10).map(Investigation::summary).toList();
        Map<PatchStatus, Long> pc = new EnumMap<>(PatchStatus.class);
        for (DataPatch d : g.patches.list()) pc.merge(d.status(), 1L, Long::sum);
        Map<String, Object> out = Maps.of(
                "serverTime", Instant.now(),
                "overview", g.engine.overview(),
                "programs", g.engine.programs(),
                "alerts", g.monitor.alerts(),
                "investigations", invs,
                "patchCounts", pc,
                "config", g.config.publicView(g.llm.providerName(), g.llm.model()));
        g.metrics.time("dashboard.snapshotBuild", System.nanoTime() - t0);
        return out;
    }

    public Map<String, Object> metrics() {
        var mx = g.metrics;
        long inv = mx.counter("supervisor.investigationsStarted");
        long ok = mx.counter("investigation.successful");
        long llmCalls = mx.counter("llm.calls");
        long tokens = mx.counter("llm.promptTokens") + mx.counter("llm.completionTokens");
        double cost = mx.sum("llm.costUsd");
        long evals = mx.counter("accuracy.evaluated");
        long resumed = mx.counter("recovery.resumed");
        Map<String, Object> agents = mx.timers("agent.");
        double speedups = 0;
        int n = 0;
        for (Investigation i : g.supervisor.list()) {
            Object s = i.performance().get("parallelSpeedup");
            if (s instanceof Number num) { speedups += num.doubleValue(); n++; }
        }
        return Maps.of(
                "llm", Maps.of("provider", g.llm.providerName(), "model", g.llm.model(), "calls", llmCalls,
                        "cacheHits", mx.counter("llm.cacheHits"), "fallbacks", mx.counter("llm.fallbacks"),
                        "promptTokens", mx.counter("llm.promptTokens"), "completionTokens", mx.counter("llm.completionTokens"),
                        "tokensPerInvestigation", inv == 0 ? 0 : tokens / inv, "llmCallsPerInvestigation", inv == 0 ? 0 : (double) llmCalls / inv,
                        "costUsd", Math.round(cost * 1e6) / 1e6, "costPerSuccessfulInvestigationUsd", ok == 0 ? 0 : Math.round(cost / ok * 1e6) / 1e6,
                        "latency", mx.timers("llm.").get("latency")),
                "investigations", Maps.of("started", inv, "successful", ok,
                        "duplicateTriggersCoalesced", mx.counter("supervisor.duplicateTriggersCoalesced"),
                        "agentInvocations", mx.counter("investigation.agentInvocations"),
                        "redundantInvocations", mx.counter("investigation.redundantInvocations"),
                        "avgParallelSpeedup", n == 0 ? 0 : Math.round(speedups / n * 100) / 100.0,
                        "endToEnd", mx.timers("investigation.").get("endToEnd"), "fanOut", mx.timers("investigation.").get("fanOut")),
                "agents", agents,
                "accuracy", Maps.of("evaluated", evals,
                        "rootCauseAccuracyPct", evals == 0 ? null : Math.round(mx.counter("accuracy.rootCauseCorrect") * 1000.0 / evals) / 10.0,
                        "patchAccuracyPct", evals == 0 ? null : Math.round(mx.counter("accuracy.patchCorrect") * 1000.0 / evals) / 10.0),
                "recovery", Maps.of("resumed", resumed, "succeeded", mx.counter("recovery.succeeded"), "failedAgain", mx.counter("recovery.failedAgain"),
                        "blocked", mx.counter("recovery.blocked"), "duplicateResumePrevented", mx.counter("recovery.duplicateResumePrevented"),
                        "successRatePct", resumed == 0 ? null : Math.round(mx.counter("recovery.succeeded") * 1000.0 / resumed) / 10.0,
                        "duplicatePostingsPrevented", g.engine.duplicatePostingsPrevented()),
                "dashboard", Maps.of("ssePush", mx.timers("sse.").get("push"), "snapshotBuild", mx.timers("dashboard.").get("snapshotBuild"),
                        "connectedStreams", mx.counter("sse.connected") - mx.counter("sse.disconnected"), "targetRefreshMs", 2000),
                "monitor", Maps.of("detectionLatency", mx.timers("monitor.").get("detectionLatency")),
                "api", Maps.of("throughputPerSec", mx.throughputPerSecond(), "routes", mx.timers("api.")),
                "audit", Maps.of("completenessPct", g.compliance.report().get("auditCompletenessPct"), "records", g.audit.size(),
                        "chainValid", g.audit.verify().get("valid")));
    }

    // ------------------------------------------------------------------ helpers

    private static Matcher match(String regex, String path) {
        Matcher m = Pattern.compile("^" + regex + "$").matcher(path);
        return m.matches() ? m : null;
    }

    private static String id(String v) {
        if (v == null || !ID.matcher(v).matches()) throw GuardianException.badRequest("INVALID_ID", "Invalid identifier");
        return v.toUpperCase(Locale.ROOT);
    }

    private static Scenario scenario(String s) {
        if (s == null) return Scenario.SUCCESS;
        try { return Scenario.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw GuardianException.badRequest("UNKNOWN_SCENARIO", "Unknown scenario " + s); }
    }
}
