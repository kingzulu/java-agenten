package com.zuluindustries.agenten.model;

import java.util.Map;

/** Ein vom Modell angeforderter Toolaufruf in providerneutraler Form. */
public record ToolCall(String name, Map<String, Object> arguments, String callId) {

    public ToolCall {
        arguments = arguments == null ? Map.of() : arguments;
    }
}
