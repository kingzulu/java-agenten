package com.zuluindustries.agenten.model.scaleway;

import com.fasterxml.jackson.databind.JsonNode;
import com.zuluindustries.agenten.model.HttpProviderSupport;
import com.zuluindustries.agenten.model.HttpTransport;
import com.zuluindustries.agenten.model.Message;
import com.zuluindustries.agenten.model.ModelProvider;
import com.zuluindustries.agenten.model.ModelRequest;
import com.zuluindustries.agenten.model.ModelResponse;
import com.zuluindustries.agenten.model.ProviderException;
import com.zuluindustries.agenten.model.TokenUsage;
import com.zuluindustries.agenten.model.ToolCall;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Adapter für Scaleway Generative APIs (OpenAI-kompatible Chat Completions);
 * entspricht {@code models/scaleway_provider.py}. Nur freigegebene Modelle,
 * keine Tools und keine Folgeaufrufe.
 */
public final class ScalewayProvider implements ModelProvider {

    public static final String NAME = "scaleway";
    public static final Set<String> ALLOWED_MODELS = Set.of("mistral-small-3.2-24b-instruct-2506");
    private static final String BASE_URL = "https://api.scaleway.ai/v1";

    private final HttpTransport transport;
    private final Function<String, String> environment;

    public ScalewayProvider(HttpTransport transport, Function<String, String> environment) {
        this.transport = transport;
        this.environment = environment;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ModelResponse createResponse(ModelRequest request) {
        if (!ALLOWED_MODELS.contains(request.model())) {
            throw new IllegalArgumentException("Scaleway-Modell nicht freigegeben: " + request.model());
        }
        if (!request.tools().isEmpty()) {
            throw new IllegalArgumentException("Der Scaleway-Adapter unterstützt keine Tools.");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", request.instructions()));
        for (Message message : request.input()) {
            messages.add(Map.of("role", message.role(), "content", message.content()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", messages);
        body.put("temperature", request.modelConfig().temperature());
        body.put("max_tokens", request.modelConfig().maxOutputTokens());

        String key = HttpProviderSupport.requireKey(environment, "SCW_SECRET_KEY");
        JsonNode response;
        try {
            response = HttpProviderSupport.post(transport, "Scaleway", URI.create(BASE_URL + "/chat/completions"),
                    Map.of("Authorization", "Bearer " + key), body, request.timeout());
        } catch (ProviderException error) {
            throw new ProviderException("Scaleway-Textmodell konnte nicht aufgerufen werden.", error);
        }
        String returnedModel = response.path("model").asText(null);
        if (returnedModel != null && !ALLOWED_MODELS.contains(returnedModel)) {
            throw new ProviderException("Scaleway hat ein nicht freigegebenes Modell zurückgegeben.");
        }
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty() || choices.get(0).path("message").isMissingNode()) {
            throw new ProviderException("Scaleway-Antwort enthält keinen Text.");
        }
        JsonNode usage = response.path("usage");
        long in = usage.path("prompt_tokens").asLong();
        long out = usage.path("completion_tokens").asLong();
        long total = usage.has("total_tokens") ? usage.path("total_tokens").asLong() : in + out;
        long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong();
        return new ModelResponse(response.path("id").asText(null),
                choices.get(0).path("message").path("content").asText(""), true, List.of(),
                new TokenUsage(in, out, total, cached, 0), null);
    }

    @Override
    public ModelResponse createFollowup(ModelRequest request, ModelResponse previous,
                                        List<ToolCall> toolCalls, List<Object> results) {
        throw new IllegalArgumentException("Scaleway unterstützt keine Folgeaufrufe.");
    }
}
