package com.caixa.eodguardian.agent;

/** A specialist worker. Agents are stateless; all state lives in the {@link AgentTask}. */
public interface Agent {
    String name();

    /** One-line description of the agent's responsibility (shown in the UI architecture view). */
    String role();

    AgentResult run(AgentTask task);
}
