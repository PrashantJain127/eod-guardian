package com.caixa.eodguardian.agent;

import java.util.List;
import java.util.Map;

/** Output of one agent invocation, including cost/latency accounting. */
public record AgentResult(String agent, String status, String summary, List<Finding> findings, List<Evidence> evidence,
                          long latencyMs, int dataQueries, int llmCalls, int tokens, int iteration, Map<String, Object> data) {

    public static AgentResult skipped(String agent, String reason) {
        return new AgentResult(agent, "SKIPPED", reason, List.of(), List.of(), 0, 0, 0, 0, 0, Map.of());
    }
}
