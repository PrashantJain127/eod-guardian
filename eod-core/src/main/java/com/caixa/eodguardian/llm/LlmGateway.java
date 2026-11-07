package com.caixa.eodguardian.llm;

import com.caixa.eodguardian.common.GuardianConfig;
import com.caixa.eodguardian.metrics.MetricsRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single entry point for every LLM call: provider selection, response cache (avoids redundant
 * calls for identical evidence), automatic fallback to the grounded mock, and token/cost metrics.
 */
public class LlmGateway {
    private static final Logger LOG = Logger.getLogger(LlmGateway.class.getName());

    private final LlmProvider primary;
    private final LlmProvider fallback = new MockLlmProvider();
    private final MetricsRegistry metrics;
    private final Map<String, LlmProvider.LlmResponse> cache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, LlmProvider.LlmResponse> e) { return size() > 200; }
            });

    public LlmGateway(LlmProvider primary, MetricsRegistry metrics) {
        this.primary = primary;
        this.metrics = metrics;
    }

    public static LlmProvider fromConfig(GuardianConfig c) {
        String p = c.llmProvider() == null ? "auto" : c.llmProvider().toLowerCase();
        boolean azureReady = c.azureEndpoint() != null && c.azureApiKey() != null;
        boolean openAiReady = c.openAiApiKey() != null;
        if (p.equals("azure") || (p.equals("auto") && azureReady && !openAiReady)) {
            if (azureReady)
                return new OpenAiCompatibleProvider(OpenAiCompatibleProvider.Flavor.AZURE, c.azureEndpoint(), c.azureApiKey(),
                        c.azureDeployment(), c.azureApiVersion(), c.llmInputCostPer1k(), c.llmOutputCostPer1k());
            LOG.warning("EOD_LLM_PROVIDER=azure but AZURE_OPENAI_ENDPOINT/AZURE_OPENAI_API_KEY not set - using mock provider");
        } else if (p.equals("openai") || p.equals("auto")) {
            if (openAiReady)
                return new OpenAiCompatibleProvider(OpenAiCompatibleProvider.Flavor.OPENAI, c.openAiBaseUrl(), c.openAiApiKey(),
                        c.openAiModel(), null, c.llmInputCostPer1k(), c.llmOutputCostPer1k());
            if (p.equals("openai")) LOG.warning("EOD_LLM_PROVIDER=openai but OPENAI_API_KEY not set - using mock provider");
        }
        return new MockLlmProvider();
    }

    public LlmProvider.LlmResponse complete(LlmProvider.LlmRequest req) {
        String key = req.purpose() + "\u0000" + req.systemPrompt() + "\u0000" + req.userPrompt();
        LlmProvider.LlmResponse hit = cache.get(key);
        if (hit != null) {
            metrics.increment("llm.cacheHits");
            return new LlmProvider.LlmResponse(hit.text(), 0, 0, 0, hit.provider(), hit.model(), true, hit.fallbackReason());
        }
        LlmProvider.LlmResponse resp;
        try {
            resp = primary.complete(req);
            cost(primary, resp);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "LLM provider " + primary.name() + " failed, falling back to grounded mock: " + ex.getMessage());
            metrics.increment("llm.fallbacks");
            LlmProvider.LlmResponse m = fallback.complete(req);
            resp = new LlmProvider.LlmResponse(m.text(), m.promptTokens(), m.completionTokens(), m.latencyMs(), m.provider(),
                    m.model(), false, primary.name() + " unavailable: " + ex.getMessage());
        }
        metrics.increment("llm.calls");
        metrics.add("llm.promptTokens", resp.promptTokens());
        metrics.add("llm.completionTokens", resp.completionTokens());
        metrics.time("llm.latency", resp.latencyMs() * 1_000_000);
        cache.put(key, resp);
        return resp;
    }

    public double costOf(LlmProvider.LlmResponse r) {
        if (r.cached() || r.fallbackReason() != null) return 0;
        return r.promptTokens() / 1000.0 * primary.inputCostPer1k() + r.completionTokens() / 1000.0 * primary.outputCostPer1k();
    }

    private void cost(LlmProvider p, LlmProvider.LlmResponse r) { metrics.addDouble("llm.costUsd", costOf(r)); }

    public String providerName() { return primary.name(); }

    public String model() { return primary.model(); }
}
