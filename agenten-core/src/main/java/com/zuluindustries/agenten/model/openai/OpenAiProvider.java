package com.zuluindustries.agenten.model.openai;

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
import com.zuluindustries.agenten.model.ToolStrategy;
import com.zuluindustries.agenten.tool.ToolSpec;
import com.zuluindustries.agenten.util.Json;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Adapter für die OpenAI Responses API ({@code POST /v1/responses});
 * entspricht {@code models/openai_provider.py}. Folgeaufrufe verweisen über
 * {@code previous_response_id} auf die vorherige Antwort.
 */
public final class OpenAiProvider implements ModelProvider {

    public static final String NAME = "openai";
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final HttpTransport transport;
    private final Function<String, String> environment;

    public OpenAiProvider(HttpTransport transport, Function<String, String> environment) {
        this.transport = transport;
        this.environment = environment;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ModelResponse createResponse(ModelRequest request) {
        Map<String, Object> body = baseBody(request);
        List<Map<String, Object>> input = new ArrayList<>();
        for (Message message : request.input()) {
            input.add(Map.of("role", message.role(), "content", message.content()));
        }
        body.put("input", input);
        if (request.modelConfig().store() != null) {
            body.put("store", request.modelConfig().store());
        }
        return send(body, request);
    }

    @Override
    public ModelResponse createFollowup(ModelRequest request, ModelResponse previous,
                                        List<ToolCall> toolCalls, List<Object> results) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (int i = 0; i < Math.min(toolCalls.size(), results.size()); i++) {
            outputs.add(Map.of(
                    "type", "function_call_output",
                    "call_id", toolCalls.get(i).callId(),
                    "output", Json.write(results.get(i))));
        }
        Map<String, Object> body = baseBody(request);
        body.put("previous_response_id", previous.id());
        body.put("input", outputs);
        return send(body, request);
    }

    private Map<String, Object> baseBody(ModelRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("instructions", request.instructions());
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSpec tool : request.tools()) {
            tools.add(Map.of(
                    "type", "function",
                    "name", tool.name(),
                    "description", tool.description(),
                    "parameters", tool.parameters()));
        }
        body.put("tools", tools);
        body.put("temperature", request.modelConfig().temperature());
        body.put("max_output_tokens", request.modelConfig().maxOutputTokens());
        boolean hasTools = !tools.isEmpty();
        body.put("tool_choice", !hasTools ? "none" : switch (request.toolStrategy()) {
            case AUTO -> "auto";
            case REQUIRED -> "required";
            case MANUAL -> "none";
        });
        if (hasTools && request.toolStrategy() == ToolStrategy.REQUIRED) {
            body.put("parallel_tool_calls", false);
        }
        return body;
    }

    private ModelResponse send(Map<String, Object> body, ModelRequest request) {
        String key = HttpProviderSupport.requireKey(environment, "OPENAI_API_KEY");
        String baseUrl = environment.apply("OPENAI_BASE_URL");
        URI uri = URI.create((baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl) + "/responses");
        JsonNode response = HttpProviderSupport.post(transport, "OpenAI", uri,
                Map.of("Authorization", "Bearer " + key), body, request.timeout());
        return parse(response);
    }

    static ModelResponse parse(JsonNode response) {
        List<ToolCall> toolCalls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (JsonNode item : response.path("output")) {
            String type = item.path("type").asText();
            if ("function_call".equals(type)) {
                Map<String, Object> arguments;
                try {
                    arguments = Json.readMap(item.path("arguments").asText("{}"));
                } catch (RuntimeException error) {
                    throw new ProviderException("Ungültige Toolargumente von OpenAI.", error);
                }
                toolCalls.add(new ToolCall(item.path("name").asText(), arguments, item.path("call_id").asText()));
            } else if ("message".equals(type)) {
                for (JsonNode content : item.path("content")) {
                    if ("output_text".equals(content.path("type").asText())) {
                        text.append(content.path("text").asText());
                    }
                }
            }
        }
        JsonNode usage = response.path("usage");
        // input_tokens enthält die Cache-Treffer bereits (input_tokens_details.cached_tokens).
        TokenUsage tokens = new TokenUsage(
                usage.path("input_tokens").asLong(),
                usage.path("output_tokens").asLong(),
                usage.path("total_tokens").asLong(),
                usage.path("input_tokens_details").path("cached_tokens").asLong(),
                0);
        boolean complete = "completed".equals(response.path("status").asText("completed"));
        return new ModelResponse(response.path("id").asText(null), text.toString(), complete,
                toolCalls, tokens, null);
    }
}
