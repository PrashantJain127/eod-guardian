package com.caixa.eodguardian.llm;

import com.caixa.eodguardian.common.Json;
import com.caixa.eodguardian.common.Maps;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions client that also speaks Azure OpenAI.
 * <ul>
 *   <li>OpenAI: POST {baseUrl}/v1/chat/completions with "Authorization: Bearer KEY"</li>
 *   <li>Azure : POST {endpoint}/openai/deployments/{deployment}/chat/completions?api-version=V with "api-key: KEY"</li>
 * </ul>
 * The API key is read from configuration/environment only and never logged or returned by the API.
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    public enum Flavor { OPENAI, AZURE }

    private final Flavor flavor;
    private final String baseUrl;
    private final String apiKey;
    private final String model;          // OpenAI model or Azure deployment name
    private final String apiVersion;
    private final double inCost;
    private final double outCost;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public OpenAiCompatibleProvider(Flavor flavor, String baseUrl, String apiKey, String model, String apiVersion,
                                    double inCostPer1k, double outCostPer1k) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("API key is required for " + flavor);
        this.flavor = flavor;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.apiVersion = apiVersion;
        this.inCost = inCostPer1k;
        this.outCost = outCostPer1k;
    }

    @Override public String name() { return flavor == Flavor.AZURE ? "azure-openai" : "openai"; }
    @Override public String model() { return model; }
    @Override public double inputCostPer1k() { return inCost; }
    @Override public double outputCostPer1k() { return outCost; }

    @Override
    public LlmResponse complete(LlmRequest req) {
        long t0 = System.nanoTime();
        Map<String, Object> body = Maps.of(
                "messages", List.of(
                        Maps.of("role", "system", "content", req.systemPrompt()),
                        Maps.of("role", "user", "content", req.userPrompt())));
        boolean reasoningModel = model.startsWith("o") || model.startsWith("gpt-5");
        if (!reasoningModel) body.put("temperature", 0);      // reasoning models reject temperature
        if (flavor == Flavor.OPENAI) {
            body.put("model", model);
            body.put("max_completion_tokens", req.maxTokens());
        } else {
            body.put("max_tokens", req.maxTokens());
        }

        String url = flavor == Flavor.AZURE
                ? baseUrl + "/openai/deployments/" + model + "/chat/completions?api-version=" + apiVersion
                : baseUrl + "/v1/chat/completions";
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (flavor == Flavor.AZURE) b.header("api-key", apiKey); else b.header("Authorization", "Bearer " + apiKey);

        try {
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2)
                throw new IllegalStateException(name() + " HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
            Map<String, Object> json = Json.parseObject(resp.body());
            List<Object> choices = Json.arr(json, "choices");
            if (choices.isEmpty()) throw new IllegalStateException(name() + " returned no choices");
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = Json.obj((Map<String, Object>) choices.get(0), "message");
            String text = msg == null ? "" : Json.str(msg, "content");
            Map<String, Object> usage = Json.obj(json, "usage");
            int pt = usage == null ? MockLlmProvider.estimateTokens(req.userPrompt()) : ((Number) usage.get("prompt_tokens")).intValue();
            int ct = usage == null ? MockLlmProvider.estimateTokens(text) : ((Number) usage.get("completion_tokens")).intValue();
            return new LlmResponse(text, pt, ct, (System.nanoTime() - t0) / 1_000_000, name(), model, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM call interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(name() + " unreachable: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s; }
}
