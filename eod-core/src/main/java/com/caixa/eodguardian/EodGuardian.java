package com.caixa.eodguardian;

import com.caixa.eodguardian.agent.EodMonitoringAgent;
import com.caixa.eodguardian.agent.SupervisorAgent;
import com.caixa.eodguardian.agent.workers.DataPatchExecutionAgent;
import com.caixa.eodguardian.audit.AuditComplianceAgent;
import com.caixa.eodguardian.audit.AuditService;
import com.caixa.eodguardian.audit.AuditSink;
import com.caixa.eodguardian.common.GuardianConfig;
import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.domain.Scenario;
import com.caixa.eodguardian.eod.EodLogStore;
import com.caixa.eodguardian.eod.SimulatedEodEngine;
import com.caixa.eodguardian.flexcube.MockFlexcubeGateway;
import com.caixa.eodguardian.llm.LlmGateway;
import com.caixa.eodguardian.metrics.MetricsRegistry;
import com.caixa.eodguardian.patch.PatchService;
import com.caixa.eodguardian.recovery.EodRecoveryAgent;
import com.caixa.eodguardian.security.AuthService;
import com.caixa.eodguardian.security.Permission;
import com.caixa.eodguardian.security.UserPrincipal;

import java.time.Duration;
import java.time.LocalDate;
import java.util.logging.Logger;

/**
 * Composition root: wires the simulated FLEXCUBE adapters, EOD engine, agents and controls.
 * Both runtimes (standalone JDK server and Spring Boot) build exactly one instance of this.
 */
public class EodGuardian implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(EodGuardian.class.getName());

    public final GuardianConfig config;
    public final MetricsRegistry metrics = new MetricsRegistry();
    public final MockFlexcubeGateway gateway;
    public final EodLogStore logs = new EodLogStore();
    public final SimulatedEodEngine engine;
    public final AuditService audit;
    public final AuditComplianceAgent compliance;
    public final AuthService auth;
    public final LlmGateway llm;
    public final PatchService patches;
    public final SupervisorAgent supervisor;
    public final EodMonitoringAgent monitor;
    public final EodRecoveryAgent recovery;
    private final LocalDate businessDate;

    public EodGuardian(GuardianConfig config, AuditSink auditSink) {
        this.config = config;
        this.businessDate = LocalDate.parse(config.businessDate());
        this.gateway = new MockFlexcubeGateway(config.simIoLatencyMs());
        this.engine = new SimulatedEodEngine(gateway, logs, config.eodSpeed());
        this.audit = new AuditService(auditSink, () -> gateway.businessDate() == null ? businessDate : gateway.businessDate());
        if (!audit.chainValidOnLoad()) LOG.severe("AUDIT CHAIN VERIFICATION FAILED ON STARTUP - journal may have been tampered with");
        this.compliance = new AuditComplianceAgent(audit);
        this.auth = new AuthService(Duration.ofHours(8));
        auth.registerDemoUsers(config.demoPassword());
        this.llm = new LlmGateway(LlmGateway.fromConfig(config), metrics);
        this.patches = new PatchService(audit, new DataPatchExecutionAgent(gateway, metrics));
        this.supervisor = new SupervisorAgent(engine, gateway, logs, audit, llm, metrics, patches);
        this.monitor = new EodMonitoringAgent(engine, supervisor, audit, metrics, config.autoInvestigate());
        this.recovery = new EodRecoveryAgent(engine, supervisor, patches, audit, metrics);
        monitor.recovery(recovery);
        engine.prepare(Scenario.SUCCESS, businessDate);
        audit.record(AuditService.event("SYSTEM_STARTED").outcome("UP").detail("llmProvider", llm.providerName())
                .detail("llmModel", llm.model()).detail("auditSink", auditSink.description()));
        LOG.info("EOD Guardian ready - LLM provider: " + llm.providerName() + " (" + llm.model() + "), audit: " + auditSink.description());
    }

    /** Prepares a new business day for a scenario (does not start it). */
    public void prepareScenario(Scenario scenario, UserPrincipal user) {
        AuthService.require(user, Permission.EOD_CONTROL);
        if (supervisor.busy()) throw GuardianException.conflict("INVESTIGATION_RUNNING", "Wait for the running investigation to finish");
        if (engine.runStatus() == SimulatedEodEngine.RunStatus.RUNNING && !user.has(Permission.SCENARIO_ADMIN))
            throw GuardianException.conflict("EOD_RUNNING", "EOD is running; only an administrator can reset it");
        engine.prepare(scenario, businessDate);
        supervisor.clear();
        patches.clear();
        monitor.reset();
        audit.record(AuditService.event("EOD_PREPARED").actor(user.username(), user.primaryRole()).outcome("READY")
                .reason("Scenario " + scenario + ": " + scenario.description()).detail("runId", engine.runId()));
    }

    public void startEod(UserPrincipal user) {
        AuthService.require(user, Permission.EOD_CONTROL);
        engine.start(user.username());
        audit.record(AuditService.event("EOD_STARTED").actor(user.username(), user.primaryRole()).outcome("RUNNING")
                .detail("runId", engine.runId()).detail("scenario", engine.scenario()));
    }

    @Override
    public void close() {
        monitor.shutdown();
        supervisor.shutdown();
        engine.close();
    }
}
