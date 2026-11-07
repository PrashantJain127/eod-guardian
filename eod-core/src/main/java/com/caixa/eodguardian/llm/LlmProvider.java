package com.caixa.eodguardian.llm;

import java.util.Map;

/** Provider abstraction: mock, OpenAI or Azure OpenAI (OpenAI-compatible chat completions). */
public interface LlmProvider {

    record LlmRequest(String purpose, String systemPrompt, String userPrompt, Map<String, Object> facts, int maxTokens) {}

    record LlmResponse(String text, int promptTokens, int completionTokens, long latencyMs, String provider, String model,
                       boolean cached, String fallbackReason) {}

    String name();

    String model();

    LlmResponse complete(LlmRequest request);

    /** USD per 1K prompt / completion tokens, for cost-per-investigation reporting. */
    default double inputCostPer1k() { return 0; }

    default double outputCostPer1k() { return 0; }
}
