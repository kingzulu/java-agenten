package com.zuluindustries.agenten.agent;

import java.util.List;

/** Systemkontext, lokale Wissensquellen, RAG-Quellen und Gedächtnis. */
public record ContextConfig(
        String systemContext,
        List<String> knowledgeSources,
        List<String> ragSources,
        MemoryConfig memory,
        Integer contextLimit) {

    public ContextConfig {
        knowledgeSources = knowledgeSources == null ? List.of() : List.copyOf(knowledgeSources);
        ragSources = ragSources == null ? List.of() : List.copyOf(ragSources);
        memory = memory == null ? MemoryConfig.defaults() : memory;
        contextLimit = Validation.optionalAtLeast("context.context_limit", contextLimit, 1);
    }

    public static ContextConfig defaults() {
        return new ContextConfig(null, null, null, null, null);
    }
}
