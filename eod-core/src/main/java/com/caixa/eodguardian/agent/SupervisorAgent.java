package com.caixa.eodguardian.agent;

import com.caixa.eodguardian.agent.workers.*;
import com.caixa.eodguardian.audit.AuditService;
import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.common.Json;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.domain.Scenario;
import com.caixa.eodguardian.eod.EodLogStore;
import com.caixa.eodguardian.eod.ProgramRuntime;
import com.caixa.eodguardian.eod.SimulatedEodEngine;
import com.caixa.eodguardian.flexcube.FlexcubeGateway;
import com.caixa.eodguardian.llm.LlmGateway;
import com.caixa.eodguardian.llm.LlmProvider;
import com.caixa.eodguardian.metrics.MetricsRegistry;
import com.caixa.eodguardian.patch.DataPatch;
import com.caixa.eodguardian.patch.PatchPolicy;
import com.caixa.eodguardian.patch.PatchService;
import com.caixa.eodguardian.security.UserPrincipal;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Supervisor / Orchestrator. Runs the investigation workflow:
 * <pre>
 *  1. Triage (sequential)        FailureInvestigationAgent classifies the error
 *  2. Conditional routing        category -> set of specialist agents (others skipped with reason)
 *  3. Parallel fan-out / fan-in  specialists run concurrently on virtual threads
 *  4. Consolidation              RootCauseAnalysisAgent scores candidates
 *  5. Evaluator-critic           CriticAgent accepts / requests more evidence / escalates
 *  6. Reflection (max 1)         widened scope + skipped agents, then re-score and re-review
 *  7. Recommendation             DataPatchRecommendationAgent + policy + critic patch review
 *  8. Narrative                  single grounded LLM call (cached)
 *  9. Human-in-the-loop          maker-checker / operator actions drive the state machine
 * </pre>
 */
public class SupervisorAgent implements PatchService.Listener {
    private static final Logger LOG = Logger.getLogger(SupervisorAgent.class.getName());

    private final SimulatedEodEngine engine;
    private final FlexcubeGateway gateway;
    private final AuditService audit;
    private final LlmGateway llm;
    private final MetricsRegistry metrics;
    private final PatchService patches;
    private final PatchPolicy policy = new PatchPolicy();

    private final FailureInvestigationAgent failureAgent;
    private final AccountContractAnalysisAgent accountAgent;
    private final ParameterConfigAgent parameterAgent;
    private final ReconciliationAgent reconAgent;
    private final RootCauseAnalysisAgent rcaAgent;
    private final CriticAgent critic;
    private final DataPatchRecommendationAgent patchAgent;

    /** States in which a fresh investigation may replace the previous one for the same failure. */
    private static final Set<InvestigationState> SUPERSEDABLE = EnumSet.of(InvestigationState.ERROR, InvestigationState.REJECTED,
            InvestigationState.ROLLED_BACK, InvestigationState.NEEDS_HUMAN_REVIEW);

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Investigation> investigations = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Investigation>> inFlight = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private volatile RecoveryCoordinator recovery;

    /** Implemented by the EOD Recovery Agent; set after construction to avoid a cycle. */
    public interface RecoveryCoordinator {
        Map<String, Object> assess(String programId);
    }

    public SupervisorAgent(SimulatedEodEngine engine, FlexcubeGateway gateway, EodLogStore logs, AuditService audit,
                           LlmGateway llm, MetricsRegistry metrics, PatchService patches) {
        this.engine = engine;
        this.gateway = gateway;
        this.audit = audit;
        this.llm = llm;
        this.metrics = metrics;
        this.patches = patches;
        this.failureAgent = new FailureInvestigationAgent(gateway, logs);
        this.accountAgent = new AccountContractAnalysisAgent(gateway);
        this.parameterAgent = new ParameterConfigAgent(gateway);
        this.reconAgent = new ReconciliationAgent(gateway, engine);
        this.rcaAgent = new RootCauseAnalysisAgent(gateway);
        this.critic = new CriticAgent(gateway);
        this.patchAgent = new DataPatchRecommendationAgent(gateway, engine, patches::nextId);
        patches.addListener(this);
    }

    public void recovery(RecoveryCoordinator r) { this.recovery = r; }

    public ReconciliationAgent reconciliationAgent() { return reconAgent; }

    public List<Map<String, Object>> agentCatalog() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(Maps.of("agent", "SupervisorAgent", "pattern", "Supervisor / Orchestrator / State machine", "role", "Delegates, routes, aggregates, enforces lifecycle"));
        l.add(Maps.of("agent", "EodMonitoringAgent", "pattern", "Event-driven monitor", "role", "Real-time status, delays, dependency violations, failure detection"));
        for (Agent a : List.of(failureAgent, accountAgent, parameterAgent, reconAgent, rcaAgent, critic, patchAgent))
            l.add(Maps.of("agent", a.name(), "pattern", pattern(a), "role", a.role()));
        l.add(Maps.of("agent", "DataPatchExecutionAgent", "pattern", "Controlled adapter (human-approved only)", "role", "Applies approved patches atomically with pre/post validation and compensation"));
        l.add(Maps.of("agent", "EodRecoveryAgent", "pattern", "Sequential gate + human-in-the-loop", "role", "Restart safety checks, dependency & duplicate-posting guards, monitored resume"));
        l.add(Maps.of("agent", "AuditComplianceAgent", "pattern", "Cross-cutting observer", "role", "Hash-chained audit trail, completeness and traceability checks"));
        return l;
    }

    private static String pattern(Agent a) {
        return switch (a.name()) {
            case "FailureInvestigationAgent" -> "Sequential triage + conditional routing input";
            case "RootCauseAnalysisAgent" -> "Fan-in / result aggregation";
            case "CriticAgent" -> "Evaluator-critic + reflection trigger";
            case "DataPatchRecommendationAgent" -> "Worker (recommend only)";
            default -> "Worker (parallel fan-out)";
        };
    }

    // ------------------------------------------------------------------ entry points

    /** Starts (or joins) the investigation for the program's current failure. Duplicate triggers are coalesced. */
    public CompletableFuture<Investigation> investigateAsync(ProgramRuntime p, String trigger) {
        String key = p.id() + "#" + p.attempts();
        Investigation existing = latestFor(p.id());
        if (existing != null && existing.programAttempt() == p.attempts() && !SUPERSEDABLE.contains(existing.state())) {
            metrics.increment("supervisor.duplicateTriggersCoalesced");
            return CompletableFuture.completedFuture(existing);
        }
        return inFlight.computeIfAbsent(key, k -> {
            metrics.increment("supervisor.investigationsStarted");
            return CompletableFuture.supplyAsync(() -> {
                try { return investigate(p, trigger); }
                finally { inFlight.remove(k); }
            }, pool);
        });
    }

    public Investigation investigateNow(String programId, UserPrincipal user) {
        ProgramRuntime p = engine.program(programId);
        if (!p.status().isTerminalFailure())
            throw GuardianException.conflict("NOT_FAILED", programId + " is " + p.status() + " - nothing to investigate");
        try {
            return investigateAsync(p, user.username()).get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new GuardianException(500, "INVESTIGATION_FAILED", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ workflow

    Investigation investigate(ProgramRuntime p, String trigger) {
        long t0 = System.nanoTime();
        String id = String.format("INV-%s-%03d", gateway.businessDate().toString().replace("-", ""), seq.incrementAndGet());
        Investigation inv = new Investigation(id, p.id(), p.def().name(), engine.runId(), gateway.businessDate(), p.errorCode(),
                p.errorMessage(), p.failedRecord(), p.status().name(), p.attempts(), trigger);
        Investigation previous = latestFor(p.id());
        if (previous != null && previous.programAttempt() == p.attempts() && SUPERSEDABLE.contains(previous.state()))
            previous.transition(InvestigationState.CLOSED, trigger, "Superseded by " + id);
        investigations.put(id, inv);
        audit.record(AuditService.event("INVESTIGATION_STARTED").agent("SupervisorAgent").program(p.id()).investigation(id)
                .ref(p.failedRecord()).reason(p.errorCode() + ": " + p.errorMessage()).outcome("STARTED").detail("trigger", trigger));
        try {
            inv.transition(InvestigationState.INVESTIGATING, "agent:SupervisorAgent", "Delegating to specialist agents");
            Map<String, AgentResult> results = new LinkedHashMap<>();
            Set<String> ranOnce = new HashSet<>();
            int redundant = 0;

            // 1. triage
            AgentResult fi = runAgent(inv, failureAgent, p, 1, Map.of());
            results.put(fi.agent(), fi);
            String category = String.valueOf(fi.data().getOrDefault("category", "UNKNOWN"));
            inv.errorCategory(category);

            // 2. conditional routing
            List<Agent> selected = new ArrayList<>();
            Map<String, String> skipped = new LinkedHashMap<>();
            route(category, selected, skipped);
            inv.routing(Maps.of("category", category, "selected", selected.stream().map(Agent::name).toList(), "skipped", skipped,
                    "rule", routingRule(category)));
            for (Map.Entry<String, String> s : skipped.entrySet()) {
                AgentResult sk = AgentResult.skipped(s.getKey(), s.getValue());
                inv.addResult(sk);
                results.put(sk.agent(), sk);
            }
            audit.record(AuditService.event("ROUTING_DECISION").agent("SupervisorAgent").program(p.id()).investigation(id)
                    .reason("Error category " + category).outcome("ROUTED").detail("selected", selected.stream().map(Agent::name).toList())
                    .detail("skipped", skipped));

            // 3. parallel fan-out / fan-in
            long fanStart = System.nanoTime();
            results.putAll(fanOut(inv, p, selected, 1, Map.of()));
            long fanWall = (System.nanoTime() - fanStart) / 1_000_000;
            selected.forEach(a -> ranOnce.add(a.name() + "#1"));

            // 4-5. consolidate + critique
            AgentResult rca = runAgent(inv, rcaAgent, p, 1, results);
            results.put(rca.agent(), rca);
            AgentResult review = runAgent(inv, critic, p, 1, results);
            recordCritic(inv, review);
            String verdict = String.valueOf(review.data().get("verdict"));

            // 6. reflection
            long reflectWall = 0;
            if ("NEEDS_MORE_EVIDENCE".equals(verdict)) {
                inv.incrementReflection();
                inv.transition(InvestigationState.REFLECTING, "agent:CriticAgent", review.summary());
                audit.record(AuditService.event("REFLECTION_TRIGGERED").agent("CriticAgent").program(p.id()).investigation(id)
                        .reason(review.summary()).outcome("RE-INVESTIGATING").detail("requestedActions", review.data().get("requestedActions")));
                List<Agent> second = new ArrayList<>(List.of(failureAgent));
                for (String s : skipped.keySet()) second.add(agentByName(s));
                if (category.equals("DATA_EXCEPTION")) second.add(accountAgent);
                for (Agent a : second) if (!ranOnce.add(a.name() + "#2")) redundant++;
                long rs = System.nanoTime();
                results.putAll(fanOut(inv, p, second, 2, Map.of()));
                reflectWall = (System.nanoTime() - rs) / 1_000_000;
                fi = results.get("FailureInvestigationAgent");
                rca = runAgent(inv, rcaAgent, p, 2, results);
                results.put(rca.agent(), rca);
                review = runAgent(inv, critic, p, 2, results);
                recordCritic(inv, review);
                verdict = String.valueOf(review.data().get("verdict"));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> rootCause = new LinkedHashMap<>((Map<String, Object>) rca.data().get("rootCause"));
            rootCause.put("criticVerdict", verdict);
            inv.rootCause(rootCause);
            populateWorkspace(inv, results, p);

            if ("ACCEPTED".equals(verdict)) {
                inv.transition(InvestigationState.ROOT_CAUSE_IDENTIFIED, "agent:RootCauseAnalysisAgent",
                        rootCause.get("title") + " (" + rootCause.get("confidence") + "%)");
                audit.record(AuditService.event("ROOT_CAUSE_IDENTIFIED").agent("RootCauseAnalysisAgent").program(p.id()).investigation(id)
                        .ref(String.valueOf(rootCause.get("entityId"))).reason(String.valueOf(rootCause.get("statement")))
                        .outcome("CONFIDENCE_" + rootCause.get("confidence")).detail("evidence", rootCause.get("supportingEvidence"))
                        .detail("secondaryErrors", rootCause.get("secondaryErrors")));

                // 7. recommendation
                AgentResult rec = runAgent(inv, patchAgent, p, review.iteration(), results);
                Optional<?> maybe = (Optional<?>) rec.data().getOrDefault("patch", Optional.empty());
                if (maybe.isPresent()) {
                    DataPatch patch = (DataPatch) maybe.get();
                    long ct = System.nanoTime();
                    List<Map<String, Object>> pol = policy.evaluate(patch, gateway);
                    Map<String, Object> patchReview = critic.reviewPatch(patch, rootCause, pol);
                    metrics.time("agent.CriticAgent.patchReview", System.nanoTime() - ct);
                    patch.attachReview(pol, patchReview);
                    patches.register(patch);
                    inv.addPatch(patch.patchId());
                    boolean fit = "FIT_FOR_HUMAN_APPROVAL".equals(patchReview.get("verdict"));
                    inv.transition(fit ? InvestigationState.PATCH_RECOMMENDED : InvestigationState.NEEDS_HUMAN_REVIEW,
                            "agent:DataPatchRecommendationAgent", patch.patchId() + " " + patchReview.get("verdict"));
                    audit.record(AuditService.event("PATCH_RECOMMENDED").agent("DataPatchRecommendationAgent").program(p.id())
                            .investigation(id).patch(patch.patchId()).ref(patch.affectedEntity()).before(patch.expectedValues())
                            .after(patch.proposedValues()).reason(patch.justification()).approval("NOT_SUBMITTED")
                            .outcome(String.valueOf(patchReview.get("verdict"))).detail("table", patch.table()).detail("key", patch.key())
                            .detail("risk", patch.riskLevel()).detail("policy", pol).detail("criticReview", patchReview));
                } else {
                    inv.transition(InvestigationState.NO_PATCH_REQUIRED, "agent:DataPatchRecommendationAgent",
                            String.valueOf(rec.data().get("noPatchReason")));
                }
            } else {
                inv.transition(InvestigationState.NEEDS_HUMAN_REVIEW, "agent:CriticAgent", review.summary());
                audit.record(AuditService.event("ESCALATED_TO_HUMAN").agent("CriticAgent").program(p.id()).investigation(id)
                        .reason(review.summary()).outcome("NEEDS_HUMAN_REVIEW"));
            }

            // 8. narrative (one grounded LLM call)
            summarise(inv, rootCause, results);

            long e2e = (System.nanoTime() - t0) / 1_000_000;
            long sumWorkers = inv.runs().stream().filter(r -> !"SKIPPED".equals(r.status()))
                    .filter(r -> selected.stream().anyMatch(a -> a.name().equals(r.agent())) && r.iteration() == 1)
                    .mapToLong(AgentResult::latencyMs).sum();
            Map<String, Long> busy = new TreeMap<>();
            for (AgentResult r : inv.runs()) busy.merge(r.agent(), r.latencyMs(), Long::sum);
            Map<String, Object> util = new TreeMap<>();
            busy.forEach((k, v) -> util.put(k, Math.round(v * 1000.0 / Math.max(1, e2e)) / 10.0));
            int invocations = (int) inv.runs().stream().filter(r -> !"SKIPPED".equals(r.status())).count();
            Map<String, Object> llmInfo = inv.llm();
            inv.performance(Maps.of("endToEndMs", e2e, "fanOutWallMs", fanWall, "sumParallelAgentMs", sumWorkers,
                    "parallelSpeedup", fanWall == 0 ? 1.0 : Math.round(sumWorkers * 100.0 / fanWall) / 100.0,
                    "reflectionWallMs", reflectWall, "agentInvocations", invocations, "agentsSkippedByRouting", skipped.size(),
                    "redundantInvocations", redundant, "agentUtilizationPct", util,
                    "dataQueries", inv.runs().stream().mapToInt(AgentResult::dataQueries).sum(),
                    "llmCalls", llmInfo.getOrDefault("calls", 0), "tokens", llmInfo.getOrDefault("totalTokens", 0),
                    "costUsd", llmInfo.getOrDefault("costUsd", 0.0)));
            metrics.time("investigation.endToEnd", System.nanoTime() - t0);
            metrics.time("investigation.fanOut", fanWall * 1_000_000);
            metrics.add("investigation.agentInvocations", invocations);
            metrics.add("investigation.redundantInvocations", redundant);
            if (inv.state() == InvestigationState.PATCH_RECOMMENDED || inv.state() == InvestigationState.NO_PATCH_REQUIRED)
                metrics.increment("investigation.successful");
            scoreAccuracy(inv);
            return inv;
        } catch (RuntimeException ex) {
            LOG.log(Level.SEVERE, "Investigation " + id + " failed", ex);
            try { inv.transition(InvestigationState.ERROR, "agent:SupervisorAgent", ex.toString()); } catch (RuntimeException ignored) { }
            audit.record(AuditService.event("INVESTIGATION_ERROR").agent("SupervisorAgent").program(p.id()).investigation(id)
                    .reason(ex.toString()).outcome("ERROR"));
            return inv;
        }
    }

    private void route(String category, List<Agent> selected, Map<String, String> skipped) {
        switch (category) {
            case "DATA_EXCEPTION", "PARAMETER" -> {
                selected.add(accountAgent);
                selected.add(parameterAgent);
                skipped.put(reconAgent.name(), "Error signature is " + category + ", not an accounting break - reconciliation deferred");
            }
            case "RECONCILIATION" -> {
                selected.add(reconAgent);
                selected.add(accountAgent);
                selected.add(parameterAgent);
            }
            case "INFRASTRUCTURE" -> {
                skipped.put(accountAgent.name(), "Infrastructure error - no business data involved");
                skipped.put(parameterAgent.name(), "Infrastructure error - configuration not involved");
                skipped.put(reconAgent.name(), "Infrastructure error - no accounting impact");
            }
            default -> {
                selected.add(accountAgent);
                selected.add(parameterAgent);
                selected.add(reconAgent);
            }
        }
    }

    private static String routingRule(String category) {
        return switch (category) {
            case "DATA_EXCEPTION" -> "DATA_EXCEPTION -> Account/Contract + Parameter (cheap drift check)";
            case "PARAMETER" -> "PARAMETER -> Parameter + Account/Contract (impact scope)";
            case "RECONCILIATION" -> "RECONCILIATION -> Reconciliation + Account/Contract + Parameter";
            case "INFRASTRUCTURE" -> "INFRASTRUCTURE -> no data agents; restart guidance";
            default -> "UNKNOWN -> all specialists";
        };
    }

    private Agent agentByName(String n) {
        for (Agent a : List.of(failureAgent, accountAgent, parameterAgent, reconAgent)) if (a.name().equals(n)) return a;
        throw new IllegalArgumentException(n);
    }

    private Map<String, AgentResult> fanOut(Investigation inv, ProgramRuntime p, List<Agent> agents, int scope, Map<String, AgentResult> up) {
        List<Future<AgentResult>> futures = new ArrayList<>();
        for (Agent a : agents) futures.add(pool.submit(() -> runAgent(inv, a, p, scope, up)));
        Map<String, AgentResult> out = new LinkedHashMap<>();
        for (Future<AgentResult> f : futures) {
            try {
                AgentResult r = f.get(60, TimeUnit.SECONDS);
                out.put(r.agent(), r);
            } catch (Exception e) {
                throw new IllegalStateException("Agent execution failed: " + e.getMessage(), e);
            }
        }
        return out;
    }

    private AgentResult runAgent(Investigation inv, Agent a, ProgramRuntime p, int scope, Map<String, AgentResult> up) {
        AgentResult r = a.run(new AgentTask(inv, p, scope, up));
        inv.addResult(r);
        metrics.time("agent." + a.name(), r.latencyMs() * 1_000_000);
        metrics.increment("agent.invocations");
        audit.record(AuditService.event("AGENT_DECISION").agent(a.name()).program(p.id()).investigation(inv.id())
                .reason(r.summary()).outcome(r.status()).detail("iteration", scope).detail("latencyMs", r.latencyMs())
                .detail("dataQueries", r.dataQueries()).detail("findings", r.findings().size()));
        return r;
    }

    private void recordCritic(Investigation inv, AgentResult review) {
        inv.addCriticReview(Maps.of("iteration", review.iteration(), "verdict", review.data().get("verdict"),
                "rules", review.data().get("rules"), "summary", review.summary(), "requestedActions", review.data().get("requestedActions")));
        audit.record(AuditService.event("CRITIC_REVIEW").agent("CriticAgent").program(inv.programId()).investigation(inv.id())
                .reason(review.summary()).outcome(String.valueOf(review.data().get("verdict"))));
    }

    @SuppressWarnings("unchecked")
    private void populateWorkspace(Investigation inv, Map<String, AgentResult> results, ProgramRuntime p) {
        AgentResult fi = results.get("FailureInvestigationAgent");
        AgentResult ac = results.get("AccountContractAnalysisAgent");
        AgentResult pa = results.get("ParameterConfigAgent");
        if (fi != null) {
            inv.logEvidence((List<Map<String, Object>>) fi.data().getOrDefault("logEvidence", List.of()));
            inv.historicalIncidents((List<Map<String, Object>>) fi.data().getOrDefault("historicalMatches", List.of()));
        }
        if (ac != null) {
            inv.impactedAccounts((List<Map<String, Object>>) ac.data().getOrDefault("impactedAccounts", List.of()));
            inv.impactedContracts((List<Map<String, Object>>) ac.data().getOrDefault("impactedContracts", List.of()));
        }
        if (pa != null) inv.parameterChanges((List<Map<String, Object>>) pa.data().getOrDefault("parameterChanges", List.of()));
        List<Map<String, Object>> downstream = new ArrayList<>();
        for (String d : engine.downstreamOf(p.id())) {
            ProgramRuntime dp = engine.program(d);
            downstream.add(Maps.of("programId", d, "name", dp.def().name(), "status", dp.status(),
                    "impact", dp.def().dependsOn().contains(p.id()) ? "Directly blocked" : "Transitively blocked"));
        }
        inv.downstreamImpact(downstream);
    }

    @SuppressWarnings("unchecked")
    private void summarise(Investigation inv, Map<String, Object> rc, Map<String, AgentResult> results) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("programId", inv.programId());
        facts.put("programName", engine.program(inv.programId()).def().name());
        facts.put("status", engine.program(inv.programId()).status());
        facts.put("errorCode", inv.errorCode());
        facts.put("failedRecord", inv.failedRecord());
        facts.put("rootCause", rc.get("statement"));
        facts.put("confidence", rc.get("confidence"));
        facts.put("secondaryErrors", ((List<String>) rc.getOrDefault("secondaryErrors", List.of())).stream()
                .map(s -> s.contains("<-") ? s.substring(0, s.indexOf("<-")).trim() : s).toList());
        facts.put("evidence", ((List<String>) rc.getOrDefault("supportingEvidence", List.of())).stream().limit(6).toList());
        List<Map<String, Object>> hist = (List<Map<String, Object>>) results.get("FailureInvestigationAgent").data().getOrDefault("historicalMatches", List.of());
        hist.stream().filter(h -> (int) h.get("similarity") == 100).findFirst().ifPresent(h ->
                facts.put("historicalMatch", h.get("incidentId") + " on " + h.get("businessDate") + " (" + h.get("rootCause") + ") was resolved by: " + h.get("resolution")));
        if (!inv.patchIds().isEmpty()) {
            DataPatch p = patches.get(inv.patchIds().get(inv.patchIds().size() - 1));
            facts.put("patchSummary", p.title() + ".");
            facts.put("patchRisk", p.riskLevel());
        }
        facts.put("downstream", engine.downstreamOf(inv.programId()));

        String system = "You are an Oracle FLEXCUBE EOD operations investigator at a bank. Write a concise incident summary for the "
                + "operations team using ONLY the facts in the JSON provided. Never invent account numbers, amounts, users or causes. "
                + "Sections: What happened; Root cause (with confidence); Secondary/cascading errors; Evidence; Precedent; "
                + "Recommended correction (state that it requires maker-checker approval); Downstream impact. Max 220 words.";
        String user = "Investigation facts (JSON):\n" + Json.write(facts);
        LlmProvider.LlmResponse r = llm.complete(new LlmProvider.LlmRequest("investigation-summary", system, user, facts, 600));
        double cost = llm.costOf(r);
        metrics.time("agent.LLM.summary", r.latencyMs() * 1_000_000);
        inv.aiSummary(r.text(), Maps.of("provider", r.provider(), "model", r.model(), "calls", 1, "cached", r.cached(),
                "promptTokens", r.promptTokens(), "completionTokens", r.completionTokens(),
                "totalTokens", r.promptTokens() + r.completionTokens(), "latencyMs", r.latencyMs(),
                "costUsd", Math.round(cost * 1_000_000) / 1_000_000.0, "fallbackReason", r.fallbackReason(),
                "grounding", "Narrative generated from structured agent evidence only; findings and patches are deterministic"));
        audit.record(AuditService.event("AI_SUMMARY_GENERATED").agent("SupervisorAgent").program(inv.programId()).investigation(inv.id())
                .outcome(r.cached() ? "CACHED" : "GENERATED").detail("provider", r.provider()).detail("model", r.model())
                .detail("tokens", r.promptTokens() + r.completionTokens()).detail("fallbackReason", r.fallbackReason()));
    }

    @SuppressWarnings("unchecked")
    private void scoreAccuracy(Investigation inv) {
        Scenario sc = engine.scenario();
        if (sc.failingProgram() == null || !sc.failingProgram().equals(inv.programId())) return;
        Map<String, Object> attrs = inv.rootCause() == null ? Map.of() : (Map<String, Object>) inv.rootCause().getOrDefault("attributes", Map.of());
        boolean rcOk = sc.expectedPatchTable().equals(attrs.get("patchTable"));
        boolean patchOk = false;
        if (!inv.patchIds().isEmpty()) {
            DataPatch p = patches.get(inv.patchIds().get(0));
            patchOk = p.table().equals(sc.expectedPatchTable()) && p.changes().stream().anyMatch(c -> c.column().equals(sc.expectedPatchColumn()));
        }
        inv.accuracy(Maps.of("scenario", sc, "groundTruth", sc.description(), "rootCauseCorrect", rcOk, "patchCorrect", patchOk));
        metrics.increment("accuracy.evaluated");
        if (rcOk) metrics.increment("accuracy.rootCauseCorrect");
        if (patchOk) metrics.increment("accuracy.patchCorrect");
    }

    // ------------------------------------------------------------------ queries

    public Investigation get(String id) {
        Investigation i = investigations.get(id);
        if (i == null) throw GuardianException.notFound("Unknown investigation " + id);
        return i;
    }

    public List<Investigation> list() {
        List<Investigation> l = new ArrayList<>(investigations.values());
        l.sort(Comparator.comparing(Investigation::createdAt).reversed());
        return l;
    }

    public Investigation latestFor(String programId) {
        return investigations.values().stream().filter(i -> i.programId().equals(programId))
                .max(Comparator.comparing(Investigation::createdAt)).orElse(null);
    }

    public void clear() { investigations.clear(); inFlight.clear(); }

    public boolean busy() { return !inFlight.isEmpty(); }

    // ------------------------------------------------------------------ human-in-the-loop callbacks (state machine)

    private Investigation invOf(DataPatch p) { return investigations.get(p.investigationId()); }

    @Override public void onSubmitted(DataPatch p, UserPrincipal u) {
        Investigation i = invOf(p);
        if (i != null) i.transition(InvestigationState.AWAITING_APPROVAL, u.username(), p.patchId() + " submitted");
    }

    @Override public void onApproved(DataPatch p, UserPrincipal u) {
        Investigation i = invOf(p);
        if (i != null) i.transition(InvestigationState.APPROVED, u.username(), p.patchId() + " approved");
    }

    @Override public void onRejected(DataPatch p, UserPrincipal u) {
        Investigation i = invOf(p);
        if (i != null) i.transition(InvestigationState.REJECTED, u.username(), p.patchId() + " rejected");
    }

    @Override public void onExecuted(DataPatch p, UserPrincipal u) {
        Investigation i = invOf(p);
        if (i == null) return;
        i.transition(InvestigationState.PATCH_EXECUTED, u.username(), p.patchId() + " executed");
        RecoveryCoordinator rc = recovery;
        if (rc != null && Boolean.TRUE.equals(rc.assess(p.programId()).get("ready")))
            i.transition(InvestigationState.READY_TO_RESUME, "agent:EodRecoveryAgent", "All recovery gates passed");
    }

    @Override public void onRolledBack(DataPatch p, UserPrincipal u) {
        Investigation i = invOf(p);
        if (i != null) i.transition(InvestigationState.ROLLED_BACK, u.username(), p.patchId() + " rolled back");
    }

    @Override public String rollbackBlockedReason(DataPatch p) {
        Investigation i = invOf(p);
        if (i != null && (i.state() == InvestigationState.RESUMED || i.state() == InvestigationState.CLOSED))
            return "Program " + p.programId() + " has already been resumed on the corrected data; rolling back now would "
                    + "desynchronise postings. Raise a new investigation instead.";
        return null;
    }

    public void shutdown() { pool.shutdownNow(); }
}
