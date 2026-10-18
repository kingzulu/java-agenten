package com.zuluindustries.agenten.model;

import com.zuluindustries.agenten.agent.ModelConfig;
import com.zuluindustries.agenten.tool.ToolSpec;

import java.time.Duration;
import java.util.List;

/** Ein providerneutraler Modellaufruf. */
public record ModelRequest(
        String model,
        String instructions,
        List<Message> input,
        List<ToolSpec> tools,
        ModelConfig modelConfig,
        ToolStrategy toolStrategy,
        Duration timeout) {

    public ModelRequest {
        input = input == null ? List.of() : List.copyOf(input);
        tools = tools == null ? List.of() : List.copyOf(tools);
        modelConfig = modelConfig == null ? ModelConfig.defaults() : modelConfig;
        toolStrategy = toolStrategy == null ? ToolStrategy.AUTO : toolStrategy;
    }
}
