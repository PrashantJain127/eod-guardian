package com.caixa.eodguardian.agent;

import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.common.JsonSerializable;
import com.caixa.eodguardian.common.Maps;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Aggregate for one failure investigation and its recovery lifecycle (thread-safe). */
public final class Investigation implements JsonSerializable {
    private final String id;
    private final String programId;
    private final String programName;
    private final String runId;
    private final LocalDate businessDate;
    private final String errorCode;
    private final String errorMessage;
    private final String failedRecord;
    private final String programStatus;
    private final int programAttempt;
    private final String triggeredBy;
    private final Instant createdAt = Instant.now();

    private InvestigationState state = InvestigationState.DETECTED;
    private final List<Map<String, Object>> transitions = new ArrayList<>();
    private String errorCategory;
    private final Map<String, Object> routing = new LinkedHashMap<>();
    private final Map<String, AgentResult> agentResults = new LinkedHashMap<>();
    private final List<AgentResult> agentRuns = new ArrayList<>();
    private Map<String, Object> rootCause;
    private final List<Map<String, Object>> criticReviews = new ArrayList<>();
    private int reflectionIterations;
    private List<Map<String, Object>> impactedAccounts = List.of();
    private List<Map<String, Object>> impactedContracts = List.of();
    private List<Map<String, Object>> parameterChanges = List.of();
    private List<Map<String, Object>> historicalIncidents = List.of();
    private List<Map<String, Object>> downstreamImpact = List.of();
    private List<Map<String, Object>> logEvidence = List.of();
    private String aiSummary;
    private Map<String, Object> llm = Map.of();
    private final List<String> patchIds = new ArrayList<>();
    private Map<String, Object> performance = Map.of();
    private Map<String, Object> accuracy = Map.of();
    private Map<String, Object> recovery = Map.of();
    private Instant completedAt;

    public Investigation(String id, String programId, String programName, String runId, LocalDate businessDate,
                         String errorCode, String errorMessage, String failedRecord, String programStatus, int attempt,
                         String triggeredBy) {
        this.id = id;
        this.programId = programId;
        this.programName = programName;
        this.runId = runId;
        this.businessDate = businessDate;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.failedRecord = failedRecord;
        this.programStatus = programStatus;
        this.programAttempt = attempt;
        this.triggeredBy = triggeredBy;
        transitions.add(Maps.of("from", null, "to", state, "at", createdAt, "actor", triggeredBy, "note", "Failure detected"));
    }

    public String id() { return id; }
    public String programId() { return programId; }
    public String runId() { return runId; }
    public LocalDate businessDate() { return businessDate; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public String failedRecord() { return failedRecord; }
    public int programAttempt() { return programAttempt; }
    public Instant createdAt() { return createdAt; }

    public synchronized InvestigationState state() { return state; }

    public synchronized void transition(InvestigationState next, String actor, String note) {
        if (!state.canMoveTo(next))
            throw GuardianException.conflict("ILLEGAL_STATE_TRANSITION", "Investigation " + id + " cannot move from " + state + " to " + next);
        transitions.add(Maps.of("from", state, "to", next, "at", Instant.now(), "actor", actor, "note", note));
        state = next;
        if (next == InvestigationState.CLOSED) completedAt = Instant.now();
    }

    public synchronized void errorCategory(String c) { errorCategory = c; }
    public synchronized String errorCategory() { return errorCategory; }
    public synchronized void routing(Map<String, Object> r) { routing.clear(); routing.putAll(r); }
    public synchronized void addResult(AgentResult r) { agentResults.put(r.agent(), r); agentRuns.add(r); }
    public synchronized Map<String, AgentResult> results() { return new LinkedHashMap<>(agentResults); }
    public synchronized List<AgentResult> runs() { return new ArrayList<>(agentRuns); }
    public synchronized void rootCause(Map<String, Object> rc) { rootCause = rc; }
    public synchronized Map<String, Object> rootCause() { return rootCause; }
    public synchronized void addCriticReview(Map<String, Object> r) { criticReviews.add(r); }
    public synchronized List<Map<String, Object>> criticReviews() { return new ArrayList<>(criticReviews); }
    public synchronized void incrementReflection() { reflectionIterations++; }
    public synchronized int reflectionIterations() { return reflectionIterations; }
    public synchronized void impactedAccounts(List<Map<String, Object>> l) { impactedAccounts = l; }
    public synchronized void impactedContracts(List<Map<String, Object>> l) { impactedContracts = l; }
    public synchronized void parameterChanges(List<Map<String, Object>> l) { parameterChanges = l; }
    public synchronized void historicalIncidents(List<Map<String, Object>> l) { historicalIncidents = l; }
    public synchronized void downstreamImpact(List<Map<String, Object>> l) { downstreamImpact = l; }
    public synchronized void logEvidence(List<Map<String, Object>> l) { logEvidence = l; }
    public synchronized void aiSummary(String s, Map<String, Object> llmInfo) { aiSummary = s; llm = llmInfo; }
    public synchronized void addPatch(String patchId) { patchIds.add(patchId); }
    public synchronized List<String> patchIds() { return new ArrayList<>(patchIds); }
    public synchronized void performance(Map<String, Object> p) { performance = p; }
    public synchronized Map<String, Object> performance() { return performance; }
    public synchronized void accuracy(Map<String, Object> a) { accuracy = a; }
    public synchronized Map<String, Object> accuracy() { return accuracy; }
    public synchronized Map<String, Object> llm() { return llm; }
    public synchronized void recovery(Map<String, Object> r) { recovery = r; }

    public synchronized Map<String, Object> summary() {
        return Maps.of("investigationId", id, "programId", programId, "programName", programName, "state", state,
                "errorCode", errorCode, "failedRecord", failedRecord, "createdAt", createdAt,
                "rootCauseTitle", rootCause == null ? null : rootCause.get("title"),
                "confidence", rootCause == null ? null : rootCause.get("confidence"), "patchIds", patchIds);
    }

    @Override
    public synchronized Map<String, Object> toMap() {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentResult r : agentRuns)
            agents.add(Maps.of("agent", r.agent(), "status", r.status(), "summary", r.summary(), "latencyMs", r.latencyMs(),
                    "dataQueries", r.dataQueries(), "llmCalls", r.llmCalls(), "tokens", r.tokens(), "iteration", r.iteration(),
                    "findings", r.findings(), "evidence", r.evidence()));
        return Maps.of(
                "investigationId", id, "programId", programId, "programName", programName, "runId", runId,
                "businessDate", businessDate, "errorCode", errorCode, "errorMessage", errorMessage, "failedRecord", failedRecord,
                "programStatus", programStatus, "programAttempt", programAttempt, "triggeredBy", triggeredBy,
                "createdAt", createdAt, "completedAt", completedAt, "state", state, "transitions", transitions,
                "errorCategory", errorCategory, "routing", routing, "agentRuns", agents, "rootCause", rootCause,
                "criticReviews", criticReviews, "reflectionIterations", reflectionIterations,
                "impactedAccounts", impactedAccounts, "impactedContracts", impactedContracts,
                "parameterChanges", parameterChanges, "historicalIncidents", historicalIncidents,
                "downstreamImpact", downstreamImpact, "logEvidence", logEvidence, "aiSummary", aiSummary, "llm", llm,
                "patchIds", patchIds, "performance", performance, "accuracy", accuracy, "recovery", recovery);
    }
}
