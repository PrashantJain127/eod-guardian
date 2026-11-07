package com.caixa.eodguardian.agent;

import com.caixa.eodguardian.eod.ProgramRuntime;

import java.util.Map;

/**
 * Context passed to a worker. Only the minimum facts each agent needs are shared
 * (compact context passing) rather than the full investigation.
 *
 * @param scope      1 = focused first pass, 2 = widened pass requested by reflection
 * @param upstream   results of agents this agent depends on (e.g. RCA receives all worker results)
 */
public record AgentTask(Investigation investigation, ProgramRuntime program, int scope, Map<String, AgentResult> upstream) {}
