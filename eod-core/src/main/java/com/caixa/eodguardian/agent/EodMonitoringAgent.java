package com.caixa.eodguardian.agent;

import com.caixa.eodguardian.audit.AuditService;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.domain.ProgramStatus;
import com.caixa.eodguardian.eod.EodListener;
import com.caixa.eodguardian.eod.ProgramRuntime;
import com.caixa.eodguardian.eod.SimulatedEodEngine;
import com.caixa.eodguardian.metrics.MetricsRegistry;
import com.caixa.eodguardian.recovery.EodRecoveryAgent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * A. EOD Monitoring Agent - event-driven watcher on the EOD batch. Detects failures (and hands
 * them to the Supervisor), abnormal execution times, dependency violations, and completion.
 */
public class EodMonitoringAgent implements EodListener {

    private final SimulatedEodEngine engine;
    private final SupervisorAgent supervisor;
    private final AuditService audit;
    private final MetricsRegistry metrics;
    private final boolean autoInvestigate;
    private volatile EodRecoveryAgent recovery;
    private final Deque<Map<String, Object>> alerts = new ConcurrentLinkedDeque<>();
    private final Set<String> delayAlerted = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "eod-monitor");
        t.setDaemon(true);
        return t;
    });

    public EodMonitoringAgent(SimulatedEodEngine engine, SupervisorAgent supervisor, AuditService audit, MetricsRegistry metrics,
                              boolean autoInvestigate) {
        this.engine = engine;
        this.supervisor = supervisor;
        this.audit = audit;
        this.metrics = metrics;
        this.autoInvestigate = autoInvestigate;
        engine.addListener(this);
        ticker.scheduleAtFixedRate(this::scanForDelays, 1, 1, TimeUnit.SECONDS);
    }

    public void recovery(EodRecoveryAgent r) { this.recovery = r; }

    @Override
    public void onProgramStatus(ProgramRuntime p, ProgramStatus from, ProgramStatus to) {
        switch (to) {
            case RUNNING -> {
                List<String> violated = p.def().dependsOn().stream().filter(d -> !engine.program(d).status().isDone()).toList();
                if (!violated.isEmpty()) alert("CRITICAL", p.id(), "Dependency violation: started before " + violated + " completed");
                if (p.attempts() == 1)
                    audit.record(AuditService.event("PROGRAM_STARTED").agent("EodMonitoringAgent").program(p.id())
                            .outcome("RUNNING").detail("records", p.totalUnits()));
            }
            case COMPLETED -> {
                audit.record(AuditService.event("PROGRAM_COMPLETED").agent("EodMonitoringAgent").program(p.id())
                        .outcome("COMPLETED").detail("elapsedMs", p.elapsedMillis()).detail("records", p.totalUnits())
                        .detail("attempt", p.attempts()));
                if (p.attempts() > 1) {
                    alert("INFO", p.id(), p.id() + " completed after resume (attempt " + p.attempts() + ")");
                    EodRecoveryAgent r = recovery;
                    if (r != null) r.onResumedProgramFinished(p);
                }
            }
            case FAILED, ABORTED -> {
                long detectMs = p.endTime() == null ? 0 : Duration.between(p.endTime(), LocalDateTime.now()).toMillis();
                metrics.time("monitor.detectionLatency", Math.max(0, detectMs) * 1_000_000);
                alert("CRITICAL", p.id(), p.id() + " " + to + ": " + p.errorCode() + " - " + p.errorMessage());
                audit.record(AuditService.event("PROGRAM_" + to).agent("EodMonitoringAgent").program(p.id()).ref(p.failedRecord())
                        .reason(p.errorMessage()).outcome(p.errorCode()).detail("checkpoint", p.processedUnits())
                        .detail("totalRecords", p.totalUnits()).detail("downstreamBlocked", engine.downstreamOf(p.id())));
                if (p.attempts() > 1) {
                    EodRecoveryAgent r = recovery;
                    if (r != null) r.onResumedProgramFinished(p);
                }
                if (autoInvestigate) supervisor.investigateAsync(p, "agent:EodMonitoringAgent");
            }
            case SKIPPED -> audit.record(AuditService.event("PROGRAM_SKIPPED").agent("EodMonitoringAgent").program(p.id())
                    .outcome("SKIPPED").reason("Not applicable for business date"));
            default -> { }
        }
    }

    @Override
    public void onRunStatus(String runId, String status) {
        switch (status) {
            case "RUNNING" -> { }
            case "COMPLETED" -> {
                alert("INFO", "EOD", "EOD run " + runId + " COMPLETED");
                audit.record(AuditService.event("EOD_COMPLETED").agent("EodMonitoringAgent").outcome("COMPLETED")
                        .detail("runId", runId).detail("overview", engine.overview()));
            }
            case "HALTED" -> {
                alert("CRITICAL", "EOD", "EOD run " + runId + " HALTED - awaiting recovery");
                audit.record(AuditService.event("EOD_HALTED").agent("EodMonitoringAgent").outcome("HALTED").detail("runId", runId));
            }
            default -> { }
        }
    }

    private void scanForDelays() {
        try {
            for (ProgramRuntime p : engine.programs()) {
                String k = p.id() + "#" + p.attempts();
                if (p.status() == ProgramStatus.RUNNING && p.delayed() && delayAlerted.add(k)) {
                    long expected = (long) (p.def().targetSeconds() * 1000 / engine.speed());
                    alert("WARNING", p.id(), p.id() + " running " + p.elapsedMillis() + " ms vs expected ~" + expected
                            + " ms (abnormal execution time)");
                    audit.record(AuditService.event("PROGRAM_DELAYED").agent("EodMonitoringAgent").program(p.id())
                            .outcome("DELAYED").detail("elapsedMs", p.elapsedMillis()).detail("expectedMs", expected));
                }
            }
        } catch (RuntimeException ignored) {
            // monitoring must never die
        }
    }

    private void alert(String severity, String program, String message) {
        alerts.addFirst(Maps.of("at", Instant.now(), "severity", severity, "programId", program, "message", message));
        while (alerts.size() > 50) alerts.pollLast();
    }

    public List<Map<String, Object>> alerts() { return new ArrayList<>(alerts); }

    public void reset() { alerts.clear(); delayAlerted.clear(); }

    public void shutdown() { ticker.shutdownNow(); }
}
