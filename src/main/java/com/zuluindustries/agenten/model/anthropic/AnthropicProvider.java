package com.zuluindustries.agenten.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.zuluindustries.agenten.model.HttpProviderSupport;
import com.zuluindustries.agenten.model.HttpTransport;
import com.zuluindustries.agenten.model.Message;
import com.zuluindustries.agenten.model.ModelProvider;
import com.zuluindustries.agenten.model.ModelRequest;
import com.zuluindustries.agenten.model.ModelResponse;
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
 * Adapter für die Anthropic Messages API ({@code POST /v1/messages});
 * entspricht {@code models/anthropic_provider.py}.
 *
 * <p>Die Messages API ist zustandslos. Der bisherige Nachrichtenverlauf wird
 * deshalb im {@code providerState} der Antwort mitgeführt statt – wie in Python –
 * in einem globalen Verlaufsspeicher.</p>
 *
 * <p>Die Temperatur wird nicht gesendet: Das in Python verwendete aktuelle
 * Anthropic-SDK akzeptiert sie nicht mehr, und neuere Modelle lehnen sie ab.</p>
 */
public final class AnthropicProvider implements ModelProvider {

    public static final String NAME = "anthropic";
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";
    private static final String API_VERSION = "2023-06-01";

    private final HttpTransport transport;
    private final Function<String, String> environment;

    public AnthropicProvider(HttpTransport transport, Function<String, String> environment) {
        this.transport = transport;
        this.environment = environment;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ModelResponse createResponse(ModelRequest request) {
        List<Object> messages = new ArrayList<>();
        for (Message message : request.input()) {
            messages.add(Map.of("role", message.role(), "content", message.content()));
        }
        return send(request, messages);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ModelResponse createFollowup(ModelRequest request, ModelResponse previous,
                                        List<ToolCall> toolCalls, List<Object> results) {
        State state = (State) previous.providerState();
        List<Object> messages = new ArrayList<>(state == null ? List.of() : state.messages());
        messages.add(Map.of("role", "assistant", "content", state == null ? List.of() : state.content()));
        List<Map<String, Object>> toolResults = new ArrayList<>();
        for (int i = 0; i < Math.min(toolCalls.size(), results.size()); i++) {
            Object result = results.get(i);
            toolResults.add(Map.of(
                    "type", "tool_result",
                    "tool_use_id", toolCalls.get(i).callId(),
                    "content", result instanceof String text ? text : Json.write(result)));
        }
        messages.add(Map.of("role", "user", "content", toolResults));
        return send(request, messages);
    }

    private ModelResponse send(ModelRequest request, List<Object> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.modelConfig().maxOutputTokens());
        body.put("system", request.instructions());
        body.put("messages", messages);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSpec tool : request.tools()) {
            tools.add(Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "input_schema", tool.parameters()));
        }
        body.put("tools", tools);
        if (!tools.isEmpty()) {
            body.put("tool_choice", request.toolStrategy() == ToolStrategy.REQUIRED
                    ? Map.of("type", "any", "disable_parallel_tool_use", true)
                    : Map.of("type", request.toolStrategy() == ToolStrategy.AUTO ? "auto" : "none"));
        }

        String key = HttpProviderSupport.requireKey(environment, "ANTHROPIC_API_KEY");
        String baseUrl = environment.apply("ANTHROPIC_BASE_URL");
        URI uri = URI.create((baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl) + "/messages");
        JsonNode response = HttpProviderSupport.post(transport, "Anthropic", uri,
                Map.of("x-api-key", key, "anthropic-version", API_VERSION), body, request.timeout());
        return parse(response, messages);
    }

    static ModelResponse parse(JsonNode response, List<Object> messages) {
        List<ToolCall> toolCalls = new ArrayList<>();
        String text = null;
        for (JsonNode block : response.path("content")) {
            String type = block.path("type").asText();
            if ("tool_use".equals(type)) {
                toolCalls.add(new ToolCall(block.path("name").asText(),
                        Json.JSON.convertValue(block.path("input"), Json.JSON.getTypeFactory()
                                .constructMapType(LinkedHashMap.class, String.class, Object.class)),
                        block.path("id").asText()));
            } else if ("text".equals(type) && text == null) {
                text = block.path("text").asText();
            }
        }
        JsonNode usage = response.path("usage");
        TokenUsage tokens = TokenUsage.of(usage.path("input_tokens").asLong(), usage.path("output_tokens").asLong());
        Object content = Json.toPlain(response.path("content"));
        return new ModelResponse(response.path("id").asText(null), text == null ? "" : text, true,
                toolCalls, tokens, new State(List.copyOf(messages), content));
    }

    /** Verlauf vor dieser Antwort und deren Inhaltsblöcke, für den nächsten Folgeaufruf. */
    record State(List<Object> messages, Object content) {
    }
}
