package com.caixa.eodguardian.agent.workers;

import com.caixa.eodguardian.agent.AgentTask;
import com.caixa.eodguardian.agent.Evidence;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.flexcube.FlexcubeGateway;

import java.util.*;

/**
 * D. Parameter and Configuration Analysis Agent - compares live CSTB_PARAM values with the
 * approved baseline, reviews today's change log (ticket, self-authorisation), validates new
 * values against reference data, and decides whether each change is relevant to the failure.
 */
public class ParameterConfigAgent extends BaseAgent {

    /** Which parameters each EOD program reads. */
    private static final Map<String, Set<String>> USED_BY = Map.of(
            "FXREVAL", Set.of("REVAL_RATE_TYPE", "EOD_PARALLEL_STREAMS"),
            "CLBATCH", Set.of("CL_AUTO_LIQD", "EOD_PARALLEL_STREAMS"),
            "LDACCR", Set.of("EOD_PARALLEL_STREAMS"),
            "GLRECON", Set.of("GL_RECON_TOLERANCE", "EOD_PARALLEL_STREAMS"),
            "ICACCR", Set.of("IC_ACCR_FREQ"),
            "TDMATP", Set.of("TD_AUTO_ROLLOVER"),
            "MISREFR", Set.of("MIS_REFRESH"));

    public ParameterConfigAgent(FlexcubeGateway gateway) { super(gateway); }

    @Override public String name() { return "ParameterConfigAgent"; }
    @Override public String role() { return "Detects parameter drift vs approved baseline, unauthorised changes and invalid values"; }

    @Override
    protected void execute(AgentTask task, Work w) {
        String program = task.investigation().programId();
        Set<String> used = USED_BY.getOrDefault(program, Set.of());
        Map<String, String> baseline = w.gw.baseline();
        String today = w.gw.businessDate().toString();
        List<Map<String, Object>> changes = new ArrayList<>();
        int drift = 0;

        Map<String, Map<String, Object>> todayLog = new LinkedHashMap<>();
        for (Map<String, Object> c : w.gw.paramChanges())
            if (s(c.get("CHANGED_ON")) != null && s(c.get("CHANGED_ON")).startsWith(today)) todayLog.put(s(c.get("PARAM_NAME")), c);

        for (Map<String, Object> p : w.gw.query("CSTB_PARAM", Map.of())) {
            String name = s(p.get("PARAM_NAME"));
            String val = s(p.get("PARAM_VAL"));
            String base = baseline.get(name);
            boolean drifted = base != null && !base.equals(val);
            Map<String, Object> log = todayLog.get(name);
            if (!drifted && log == null) continue;
            if (drifted) drift++;

            boolean relevant = used.contains(name);
            boolean hasTicket = log != null && log.get("CHANGE_TICKET") != null;
            boolean selfAuth = log != null && Objects.equals(log.get("CHANGED_BY"), log.get("AUTHORISED_BY"));
            String validity = validate(w, name, val);
            List<Evidence> ev = new ArrayList<>();
            ev.add(Evidence.param("CSTB_PARAM[" + name + "]", "Current '" + val + "' vs approved baseline '" + base + "'"
                    + (drifted ? " - DRIFT" : " - matches baseline")));
            if (log != null)
                ev.add(Evidence.param("Parameter change log", name + ": '" + log.get("OLD_VAL") + "' -> '" + log.get("NEW_VAL") + "' by "
                        + log.get("CHANGED_BY") + " at " + log.get("CHANGED_ON") + ", ticket " + (hasTicket ? log.get("CHANGE_TICKET") : "NONE")
                        + ", authorised by " + log.get("AUTHORISED_BY") + (selfAuth ? " (SELF-AUTHORISED)" : "")));
            if (validity != null) ev.add(Evidence.db("Reference data check", validity));

            changes.add(Maps.of("parameter", name, "currentValue", val, "baselineValue", base, "drift", drifted,
                    "changedBy", log == null ? null : log.get("CHANGED_BY"), "changedOn", log == null ? null : log.get("CHANGED_ON"),
                    "changeTicket", log == null ? null : log.get("CHANGE_TICKET"), "selfAuthorised", selfAuth,
                    "valid", validity == null, "validationMessage", validity, "usedByFailedProgram", relevant,
                    "assessment", drifted && relevant && validity != null ? "LIKELY CAUSE"
                            : drifted && relevant ? "SUSPICIOUS" : "NOT RELATED TO " + program));

            if (drifted && relevant) {
                int conf = 60 + (validity != null ? 15 : 0) + (!hasTicket ? 8 : 0) + (selfAuth ? 5 : 0)
                        + (task.investigation().errorMessage() != null && task.investigation().errorMessage().contains(val) ? 7 : 0);
                w.finding("ROOT_CANDIDATE", "PARAMETER", name, "Unapproved change to " + name,
                        name + " was changed from '" + base + "' to '" + val + "'" + (log != null ? " by " + log.get("CHANGED_BY") + " at " + log.get("CHANGED_ON") : "")
                                + (hasTicket ? "" : " without a change ticket") + (selfAuth ? ", self-authorised (maker = checker)" : "")
                                + ". " + program + " reads this parameter." + (validity != null ? " " + validity + "." : ""),
                        "CRITICAL", Math.min(conf, 95), ev,
                        Maps.of("parameter", name, "currentValue", val, "baselineValue", base, "changedBy", log == null ? null : log.get("CHANGED_BY"),
                                "patchTable", "CSTB_PARAM", "patchColumn", "PARAM_VAL"), name());
            } else {
                w.finding("RULED_OUT", "PARAMETER", name, name + " change not related",
                        name + (drifted ? " drifted" : " was maintained today") + (hasTicket ? " under approved ticket " + log.get("CHANGE_TICKET") : "")
                                + "; it is not read by " + program + ".", "LOW", 80, ev, Map.of(), name());
            }
        }
        w.data.put("parameterChanges", changes);
        w.data.put("driftCount", drift);
        w.summary = changes.isEmpty() ? "All " + baseline.size() + " EOD parameters match the approved baseline; no changes today"
                : drift + " parameter(s) drifted from baseline, " + changes.size() + " changed/maintained today; "
                + changes.stream().filter(c -> Boolean.TRUE.equals(c.get("usedByFailedProgram")) && Boolean.TRUE.equals(c.get("drift"))).count()
                + " relevant to " + program;
    }

    private String validate(Work w, String name, String val) {
        switch (name) {
            case "REVAL_RATE_TYPE" -> {
                if (w.gw.find("CYTM_RATE_TYPE", Map.of("RATE_TYPE", val)).isEmpty())
                    return "Rate type '" + val + "' does not exist in CYTM_RATE_TYPE and has no rates in CYTM_RATES";
                return null;
            }
            case "EOD_PARALLEL_STREAMS" -> {
                try { int n = Integer.parseInt(val); return n >= 1 && n <= 8 ? null : "Out of range 1-8: " + val; }
                catch (NumberFormatException e) { return "Not numeric: " + val; }
            }
            case "CL_AUTO_LIQD", "TD_AUTO_ROLLOVER", "MIS_REFRESH" -> {
                return Set.of("Y", "N").contains(val) ? null : "Must be Y/N: " + val;
            }
            default -> { return null; }
        }
    }
}
