package com.caixa.eodguardian.agent.workers;

import com.caixa.eodguardian.agent.AgentTask;
import com.caixa.eodguardian.agent.Evidence;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.domain.HistoricalIncident;
import com.caixa.eodguardian.domain.LogEntry;
import com.caixa.eodguardian.domain.ProgramRunHistory;
import com.caixa.eodguardian.domain.ProgramStatus;
import com.caixa.eodguardian.eod.EodLogStore;
import com.caixa.eodguardian.flexcube.FlexcubeGateway;

import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B. Failure Investigation Agent - parses the batch log / Oracle error stack, separates the
 * primary error from stack frames and cascading errors, classifies the error category for
 * routing, and correlates with historical incidents and previous runs.
 */
public class FailureInvestigationAgent extends BaseAgent {

    private static final Pattern ORA = Pattern.compile("(ORA-\\d{5})");
    private static final Pattern BIZ = Pattern.compile("\\b([A-Z]{2}-[A-Z]{2,5}-\\d{3})\\b");

    private final EodLogStore logs;

    public FailureInvestigationAgent(FlexcubeGateway gateway, EodLogStore logs) {
        super(gateway);
        this.logs = logs;
    }

    @Override public String name() { return "FailureInvestigationAgent"; }
    @Override public String role() { return "Analyses EOD logs, Oracle error stacks and execution traces; classifies the failure; correlates history"; }

    @Override
    protected void execute(AgentTask task, Work w) {
        String programId = task.investigation().programId();
        String errorCode = task.investigation().errorCode();
        List<LogEntry> lines = logs.forProgram(programId, 400);

        String primary = null;
        List<String> secondary = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        List<Map<String, Object>> logEvidence = new ArrayList<>();
        for (LogEntry e : lines) {
            if (!"ERROR".equals(e.level()) && !"FATAL".equals(e.level())) continue;
            String m = e.message();
            String role;
            if (m.startsWith("ORA-06512")) { role = "STACK_FRAME"; stack.add(m); }
            else if (errorCode != null && m.contains(errorCode) && primary == null) { role = "PRIMARY"; primary = m; }
            else if (m.contains("PR_LOG_EXCEPTION")) { role = "SECONDARY"; secondary.add(m + "  <- failure while logging the primary error"); }
            else if (m.contains("AEEOD-00")) { role = "CASCADING"; secondary.add(m + "  <- batch-level consequence"); }
            else if (m.startsWith("ORA-01403") || m.startsWith("ORA-20510")) { role = "SYMPTOM"; secondary.add(m + "  <- symptom of the primary error"); }
            else role = "CONTEXT";
            logEvidence.add(Maps.of("seq", e.seq(), "timestamp", e.timestamp(), "level", e.level(), "source", e.source(),
                    "message", m, "classification", role));
        }
        if (primary == null && !lines.isEmpty()) primary = task.investigation().errorMessage();

        String category = classify(errorCode, primary);
        w.data.put("category", category);
        w.data.put("primaryError", primary);
        w.data.put("secondaryErrors", secondary);
        w.data.put("stackFrames", stack);
        w.data.put("logEvidence", logEvidence);
        if (primary != null) w.evidence.add(Evidence.log(programId + " batch log", primary));
        if (!stack.isEmpty()) w.evidence.add(Evidence.log("Oracle error stack", String.join(" <- ", stack)));

        // ---- previous runs: was this program healthy until today?
        List<ProgramRunHistory> hist = w.gw.history(programId);
        long recentOk = hist.stream().filter(h -> h.status() == ProgramStatus.COMPLETED).count();
        boolean regression = !hist.isEmpty() && recentOk == hist.size();
        w.data.put("regression", regression);
        w.data.put("previousRuns", hist.size());
        if (regression)
            w.evidence.add(Evidence.history(programId + " run history", "Completed successfully in all " + hist.size()
                    + " previous runs - today's failure is a new condition (data or configuration change), not a code defect"));

        // ---- historical incidents (focused lookback first; widened on reflection)
        int lookbackDays = task.scope() >= 2 ? 365 : 30;
        List<Map<String, Object>> matches = new ArrayList<>();
        for (HistoricalIncident i : w.gw.incidents()) {
            long age = ChronoUnit.DAYS.between(i.businessDate(), w.gw.businessDate());
            if (age > lookbackDays) continue;
            int score = (i.programId().equals(programId) ? 50 : 0) + (Objects.equals(i.errorCode(), errorCode) ? 50 : 0);
            if (score == 0) continue;
            matches.add(Maps.of("incidentId", i.incidentId(), "businessDate", i.businessDate(), "programId", i.programId(),
                    "errorCode", i.errorCode(), "rootCause", i.rootCause(), "resolution", i.resolution(),
                    "resolutionMinutes", i.resolutionMinutes(), "similarity", score));
        }
        matches.sort((a, b) -> Integer.compare((int) b.get("similarity"), (int) a.get("similarity")));
        w.data.put("historicalMatches", matches);
        w.data.put("lookbackDays", lookbackDays);
        for (Map<String, Object> m : matches)
            if ((int) m.get("similarity") == 100)
                w.evidence.add(Evidence.history((String) m.get("incidentId"), "Same program and error code on "
                        + m.get("businessDate") + ": " + m.get("rootCause")));

        w.summary = "Category " + category + "; primary error " + (errorCode == null ? "unknown" : errorCode)
                + "; " + secondary.size() + " secondary/cascading error(s) separated; "
                + matches.size() + " similar incident(s) in last " + lookbackDays + " days";
        w.finding("INFORMATIONAL", "PROGRAM", programId, "Primary error isolated",
                "Primary: " + primary + ". " + secondary.size() + " secondary error(s) are consequences, not causes.",
                "HIGH", 90, w.evidence, Map.of("category", category), name());
    }

    static String classify(String errorCode, String primary) {
        String code = errorCode == null ? "" : errorCode;
        String text = primary == null ? "" : primary;
        if (code.startsWith("GL-RECON") || text.contains("mismatch")) return "RECONCILIATION";
        if (code.startsWith("CY-RATE") || text.contains("REVAL_RATE_TYPE") || text.contains("rate type")) return "PARAMETER";
        if (code.startsWith("CL-") || code.startsWith("TD-") || text.contains("account")) return "DATA_EXCEPTION";
        Matcher m = ORA.matcher(text);
        if (m.find() && Set.of("ORA-01555", "ORA-00060", "ORA-04031", "ORA-01652").contains(m.group(1))) return "INFRASTRUCTURE";
        if (BIZ.matcher(text).find()) return "DATA_EXCEPTION";
        return "UNKNOWN";
    }
}
