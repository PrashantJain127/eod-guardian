package com.caixa.eodguardian.common;

import java.util.Map;
import java.util.function.Function;

/**
 * Externalised configuration. Every key can be supplied as an environment variable
 * (EOD_LLM_PROVIDER) or a JVM system property (-Deod.llm.provider). Secrets (API keys,
 * passwords) are only ever read from the environment / secret store, never from source.
 */
public record GuardianConfig(
        int port,
        String dataDir,
        double eodSpeed,
        int simIoLatencyMs,
        String businessDate,
        String demoPassword,
        String llmProvider,          // auto | mock | openai | azure
        String openAiApiKey,
        String openAiBaseUrl,
        String openAiModel,
        String azureEndpoint,
        String azureApiKey,
        String azureDeployment,
        String azureApiVersion,
        double llmInputCostPer1k,
        double llmOutputCostPer1k,
        boolean autoInvestigate) {

    public static GuardianConfig fromEnvironment() { return from(System.getenv(), System::getProperty); }

    public static GuardianConfig from(Map<String, String> env, Function<String, String> props) {
        Function<String, String> get = key -> {
            String p = props.apply(key.toLowerCase().replace('_', '.'));
            if (p != null && !p.isBlank()) return p;
            String e = env.get(key);
            return e == null || e.isBlank() ? null : e;
        };
        return new GuardianConfig(
                intOr(get.apply("EOD_PORT"), 8080),
                or(get.apply("EOD_DATA_DIR"), "./data"),
                dblOr(get.apply("EOD_SPEED"), 1.0),
                intOr(get.apply("EOD_SIM_IO_LATENCY_MS"), 60),
                or(get.apply("EOD_BUSINESS_DATE"), "2026-10-30"),
                or(get.apply("EOD_DEMO_PASSWORD"), "Guardian@2026"),
                or(get.apply("EOD_LLM_PROVIDER"), "auto"),
                get.apply("OPENAI_API_KEY"),
                or(get.apply("OPENAI_BASE_URL"), "https://api.openai.com"),
                or(get.apply("OPENAI_MODEL"), "gpt-4o-mini"),
                get.apply("AZURE_OPENAI_ENDPOINT"),
                get.apply("AZURE_OPENAI_API_KEY"),
                or(get.apply("AZURE_OPENAI_DEPLOYMENT"), "gpt-4o-mini"),
                or(get.apply("AZURE_OPENAI_API_VERSION"), "2024-10-21"),
                dblOr(get.apply("EOD_LLM_INPUT_COST_PER_1K"), 0.00015),
                dblOr(get.apply("EOD_LLM_OUTPUT_COST_PER_1K"), 0.0006),
                !"false".equalsIgnoreCase(get.apply("EOD_AUTO_INVESTIGATE")));
    }

    /** Test/demo profile: fast EOD, no simulated I/O latency, mock LLM. */
    public static GuardianConfig fastTest() {
        return new GuardianConfig(0, null, 25.0, 0, "2026-10-30", "Guardian@2026", "mock", null,
                "https://api.openai.com", "gpt-4o-mini", null, null, "gpt-4o-mini", "2024-10-21", 0.00015, 0.0006, true);
    }

    public GuardianConfig withLatency(int ms) {
        return new GuardianConfig(port, dataDir, eodSpeed, ms, businessDate, demoPassword, llmProvider, openAiApiKey,
                openAiBaseUrl, openAiModel, azureEndpoint, azureApiKey, azureDeployment, azureApiVersion,
                llmInputCostPer1k, llmOutputCostPer1k, autoInvestigate);
    }

    /** Safe view for the UI/API - never exposes secrets. */
    public Map<String, Object> publicView(String effectiveProvider, String model) {
        return Maps.of("llmProvider", effectiveProvider, "llmModel", model, "eodSpeed", eodSpeed,
                "simIoLatencyMs", simIoLatencyMs, "autoInvestigate", autoInvestigate,
                "openAiKeyConfigured", openAiApiKey != null, "azureConfigured", azureEndpoint != null && azureApiKey != null);
    }

    private static String or(String v, String d) { return v == null ? d : v; }
    private static int intOr(String v, int d) { try { return v == null ? d : Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return d; } }
    private static double dblOr(String v, double d) { try { return v == null ? d : Double.parseDouble(v.trim()); } catch (NumberFormatException e) { return d; } }
}
