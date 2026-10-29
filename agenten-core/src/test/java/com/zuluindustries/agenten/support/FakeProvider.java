package com.zuluindustries.agenten.support;

import com.zuluindustries.agenten.model.ModelProvider;
import com.zuluindustries.agenten.model.ModelRequest;
import com.zuluindustries.agenten.model.ModelResponse;
import com.zuluindustries.agenten.model.TokenUsage;
import com.zuluindustries.agenten.model.ToolCall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Skriptgesteuerter Provider ohne Netzwerkzugriff. */
public class FakeProvider implements ModelProvider {

    public record Followup(ModelRequest request, ModelResponse previous, List<ToolCall> toolCalls,
                           List<Object> results) {
    }

    private final String name;
    private final Deque<Supplier<ModelResponse>> script = new ArrayDeque<>();
    public final List<ModelRequest> requests = new ArrayList<>();
    public final List<Followup> followups = new ArrayList<>();

    public FakeProvider() {
        this("fake");
    }

    public FakeProvider(String name) {
        this.name = name;
    }

    public static ModelResponse text(String text) {
        return new ModelResponse("r", text, true, List.of(), TokenUsage.of(10, 5), null);
    }

    public static ModelResponse tool(String name, Map<String, Object> arguments) {
        return new ModelResponse("r", "", true, List.of(new ToolCall(name, arguments, "call-" + name)),
                TokenUsage.of(10, 5), null);
    }

    public FakeProvider then(ModelResponse response) {
        script.add(() -> response);
        return this;
    }

    public FakeProvider thenFail(RuntimeException error) {
        script.add(() -> {
            throw error;
        });
        return this;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ModelResponse createResponse(ModelRequest request) {
        requests.add(request);
        return next();
    }

    @Override
    public ModelResponse createFollowup(ModelRequest request, ModelResponse previous, List<ToolCall> toolCalls,
                                        List<Object> results) {
        requests.add(request);
        followups.add(new Followup(request, previous, List.copyOf(toolCalls), List.copyOf(results)));
        return next();
    }

    private ModelResponse next() {
        if (script.isEmpty()) {
            throw new IllegalStateException("Keine weitere Antwort im Skript.");
        }
        return script.poll().get();
    }
}
