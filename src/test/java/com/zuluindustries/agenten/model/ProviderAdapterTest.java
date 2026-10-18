package com.zuluindustries.agenten.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.zuluindustries.agenten.agent.ModelConfig;
import com.zuluindustries.agenten.model.anthropic.AnthropicProvider;
import com.zuluindustries.agenten.model.openai.OpenAiProvider;
import com.zuluindustries.agenten.model.scaleway.ScalewayProvider;
import com.zuluindustries.agenten.tool.ToolSpec;
import com.zuluindustries.agenten.util.Json;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderAdapterTest {

    record Sent(URI uri, Map<String, String> headers, JsonNode body) {
    }

    static final class RecordingTransport implements HttpTransport {
        final List<Sent> sent = new ArrayList<>();
        final Deque<Response> responses = new ArrayDeque<>();

        RecordingTransport respond(int status, String body) {
            responses.add(new Response(status, body));
            return this;
        }

        @Override
        public Response postJson(URI uri, Map<String, String> headers, String body, Duration timeout) {
            sent.add(new Sent(uri, headers, Json.readTree(body)));
            return responses.poll();
        }
    }

    static final Map<String, String> ENV = Map.of(
            "OPENAI_API_KEY", "sk-test", "ANTHROPIC_API_KEY", "ak-test", "SCW_SECRET_KEY", "scw-test");
    static final ToolSpec LOOKUP = new ToolSpec("lookup", "Sucht.", Map.of("type", "object", "properties", Map.of()));

    static ModelRequest request(List<ToolSpec> tools, ToolStrategy strategy) {
        return new ModelRequest("modell", "Anweisungen", List.of(Message.user("Frage")), tools,
                new ModelConfig(null, 0.4, 256, null, null), strategy, Duration.ofSeconds(10));
    }

    @Test
    void openAiBuildsResponsesRequestAndParsesToolCalls() {
        RecordingTransport transport = new RecordingTransport().respond(200, """
                {"id": "resp_1", "status": "completed",
                 "output": [{"type": "function_call", "name": "lookup", "arguments": "{\\"q\\": \\"x\\"}", "call_id": "c1"}],
                 "usage": {"input_tokens": 11, "output_tokens": 4, "total_tokens": 15}}
                """).respond(200, """
                {"id": "resp_2", "status": "completed",
                 "output": [{"type": "message", "content": [{"type": "output_text", "text": "Antwort"}]}],
                 "usage": {"input_tokens": 20, "output_tokens": 2, "total_tokens": 22}}
                """);
        OpenAiProvider provider = new OpenAiProvider(transport, ENV::get);

        ModelResponse first = provider.createResponse(request(List.of(LOOKUP), ToolStrategy.REQUIRED));
        ModelResponse second = provider.createFollowup(request(List.of(LOOKUP), ToolStrategy.AUTO), first,
                first.toolCalls(), List.of(Map.of("treffer", 1)));

        JsonNode body = transport.sent.getFirst().body();
        assertEquals("https://api.openai.com/v1/responses", transport.sent.getFirst().uri().toString());
        assertEquals("Bearer sk-test", transport.sent.getFirst().headers().get("Authorization"));
        assertEquals("Anweisungen", body.path("instructions").asText());
        assertEquals("Frage", body.path("input").get(0).path("content").asText());
        assertEquals("function", body.path("tools").get(0).path("type").asText());
        assertEquals("required", body.path("tool_choice").asText());
        assertFalse(body.path("parallel_tool_calls").asBoolean());
        assertEquals(256, body.path("max_output_tokens").asInt());
        assertEquals(new ToolCall("lookup", Map.of("q", "x"), "c1"), first.toolCalls().getFirst());
        assertEquals(new TokenUsage(11, 4, 15), first.usage());

        JsonNode followup = transport.sent.get(1).body();
        assertEquals("resp_1", followup.path("previous_response_id").asText());
        assertEquals("function_call_output", followup.path("input").get(0).path("type").asText());
        assertEquals("{\"treffer\":1}", followup.path("input").get(0).path("output").asText());
        assertEquals("auto", followup.path("tool_choice").asText());
        assertEquals("Antwort", second.outputText());
    }

    @Test
    void openAiMarksIncompleteResponsesAndReportsHttpErrors() {
        RecordingTransport transport = new RecordingTransport()
                .respond(200, "{\"id\": \"r\", \"status\": \"incomplete\", \"output\": [], \"usage\": {}}")
                .respond(429, "{\"error\": \"rate limit\"}");
        OpenAiProvider provider = new OpenAiProvider(transport, ENV::get);

        ModelResponse incomplete = provider.createResponse(request(List.of(), ToolStrategy.AUTO));
        assertThrows(ProviderException.class, incomplete::outputText);
        assertEquals("none", transport.sent.getFirst().body().path("tool_choice").asText());

        ProviderException error = assertThrows(ProviderException.class,
                () -> provider.createResponse(request(List.of(), ToolStrategy.AUTO)));
        assertTrue(error.getMessage().contains("HTTP 429"));

        assertThrows(ProviderException.class,
                () -> new OpenAiProvider(transport, name -> null).createResponse(request(List.of(), ToolStrategy.AUTO)));
    }

    @Test
    void anthropicKeepsMessageHistoryForToolResults() {
        RecordingTransport transport = new RecordingTransport().respond(200, """
                {"id": "msg_1", "content": [{"type": "text", "text": "Ich suche."},
                  {"type": "tool_use", "id": "tu_1", "name": "lookup", "input": {"q": "x"}}],
                 "usage": {"input_tokens": 9, "output_tokens": 3}}
                """).respond(200, """
                {"id": "msg_2", "content": [{"type": "text", "text": "Fertig"}],
                 "usage": {"input_tokens": 30, "output_tokens": 5}}
                """);
        AnthropicProvider provider = new AnthropicProvider(transport, ENV::get);

        ModelResponse first = provider.createResponse(request(List.of(LOOKUP), ToolStrategy.REQUIRED));
        ModelResponse second = provider.createFollowup(request(List.of(LOOKUP), ToolStrategy.AUTO), first,
                first.toolCalls(), List.of(Map.of("treffer", 1)));

        Sent initial = transport.sent.getFirst();
        assertEquals("https://api.anthropic.com/v1/messages", initial.uri().toString());
        assertEquals("ak-test", initial.headers().get("x-api-key"));
        assertEquals("Anweisungen", initial.body().path("system").asText());
        assertEquals("lookup", initial.body().path("tools").get(0).path("name").asText());
        assertTrue(initial.body().path("tools").get(0).has("input_schema"));
        assertEquals("any", initial.body().path("tool_choice").path("type").asText());
        assertTrue(initial.body().path("tool_choice").path("disable_parallel_tool_use").asBoolean());
        assertFalse(initial.body().has("temperature"));
        assertEquals(new TokenUsage(9, 3, 12), first.usage());
        assertEquals(new ToolCall("lookup", Map.of("q", "x"), "tu_1"), first.toolCalls().getFirst());

        JsonNode messages = transport.sent.get(1).body().path("messages");
        assertEquals(3, messages.size());
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertEquals("tool_use", messages.get(1).path("content").get(1).path("type").asText());
        JsonNode toolResult = messages.get(2).path("content").get(0);
        assertEquals("tool_result", toolResult.path("type").asText());
        assertEquals("tu_1", toolResult.path("tool_use_id").asText());
        assertEquals("{\"treffer\":1}", toolResult.path("content").asText());
        assertEquals("Fertig", second.outputText());
    }

    @Test
    void scalewayOnlyAllowsApprovedModelsWithoutTools() {
        RecordingTransport transport = new RecordingTransport().respond(200, """
                {"id": "c", "model": "mistral-small-3.2-24b-instruct-2506",
                 "choices": [{"message": {"content": "Text"}}],
                 "usage": {"prompt_tokens": 5, "completion_tokens": 2, "total_tokens": 7}}
                """);
        ScalewayProvider provider = new ScalewayProvider(transport, ENV::get);
        ModelRequest ok = new ModelRequest("mistral-small-3.2-24b-instruct-2506", "System",
                List.of(Message.user("Hi")), List.of(), null, null, null);

        ModelResponse response = provider.createResponse(ok);

        assertEquals("Text", response.outputText());
        assertEquals(new TokenUsage(5, 2, 7), response.usage());
        assertEquals("system", transport.sent.getFirst().body().path("messages").get(0).path("role").asText());
        assertThrows(IllegalArgumentException.class, () -> provider.createResponse(request(List.of(), ToolStrategy.AUTO)));
        assertThrows(IllegalArgumentException.class, () -> provider.createResponse(new ModelRequest(
                "mistral-small-3.2-24b-instruct-2506", "S", List.of(), List.of(LOOKUP), null, null, null)));
    }
}
