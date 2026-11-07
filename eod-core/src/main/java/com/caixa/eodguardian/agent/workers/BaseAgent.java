package com.caixa.eodguardian.agent.workers;

import com.caixa.eodguardian.agent.*;
import com.caixa.eodguardian.domain.HistoricalIncident;
import com.caixa.eodguardian.domain.ProgramRunHistory;
import com.caixa.eodguardian.flexcube.FlexcubeGateway;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Common plumbing: timing, read-only gateway with query accounting, result assembly. */
public abstract class BaseAgent implements Agent {

    protected final FlexcubeGateway gateway;

    protected BaseAgent(FlexcubeGateway gateway) { this.gateway = gateway; }

    /** Per-invocation working set. */
    protected static final class Work {
        final ReadOnlyGateway gw;
        final List<Finding> findings = new ArrayList<>();
        final List<Evidence> evidence = new ArrayList<>();
        final Map<String, Object> data = new LinkedHashMap<>();
        String summary = "";

        Work(ReadOnlyGateway gw) { this.gw = gw; }

        void finding(String category, String entityType, String entityId, String title, String description, String severity,
                     int confidence, List<Evidence> ev, Map<String, Object> attrs, String agent) {
            findings.add(new Finding(agent, category, entityType, entityId, title, description, severity, confidence,
                    List.copyOf(ev), attrs == null ? Map.of() : attrs));
        }
    }

    @Override
    public final AgentResult run(AgentTask task) {
        long t0 = System.nanoTime();
        Work w = new Work(new ReadOnlyGateway(gateway));
        String status = "COMPLETED";
        try {
            execute(task, w);
        } catch (RuntimeException e) {
            status = "ERROR";
            w.summary = name() + " failed: " + e.getMessage();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new AgentResult(name(), status, w.summary, List.copyOf(w.findings), List.copyOf(w.evidence), ms,
                w.gw.queries.get(), 0, 0, task.scope(), Map.copyOf(w.data));
    }

    protected abstract void execute(AgentTask task, Work w);

    /**
     * Read-only view of the gateway handed to investigation agents: any attempt to write
     * throws, so analysis agents are structurally unable to modify banking records.
     */
    protected static final class ReadOnlyGateway {
        private final FlexcubeGateway d;
        final AtomicInteger queries = new AtomicInteger();

        ReadOnlyGateway(FlexcubeGateway d) { this.d = d; }

        public List<Map<String, Object>> query(String t, Map<String, Object> f) { queries.incrementAndGet(); return d.query(t, f); }
        public Optional<Map<String, Object>> find(String t, Map<String, Object> k) { queries.incrementAndGet(); return d.findByKey(t, k); }
        public List<Map<String, Object>> paramChanges() { queries.incrementAndGet(); return d.parameterChangeLog(); }
        public Map<String, String> baseline() { return d.parameterBaseline(); }
        public List<HistoricalIncident> incidents() { queries.incrementAndGet(); return d.historicalIncidents(); }
        public List<ProgramRunHistory> history(String p) { return d.runHistory(p); }
        public LocalDate businessDate() { return d.businessDate(); }
        public String branch() { return d.branchCode(); }
    }

    protected static String s(Object o) { return o == null ? null : o.toString(); }
}
