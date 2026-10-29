package com.zuluindustries.agenten.agent;

import java.util.List;

/** Zusätzliche Regeln und verbotene Handlungen. */
public record GuardrailConfig(List<String> forbiddenActions, List<String> instructions) {

    public GuardrailConfig {
        forbiddenActions = forbiddenActions == null ? List.of() : List.copyOf(forbiddenActions);
        instructions = instructions == null ? List.of() : List.copyOf(instructions);
    }

    public static GuardrailConfig defaults() {
        return new GuardrailConfig(null, null);
    }
}
