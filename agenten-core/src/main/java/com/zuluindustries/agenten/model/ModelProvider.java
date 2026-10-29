package com.zuluindustries.agenten.model;

import java.util.List;

/**
 * Provideradapter. Übersetzt gemeinsame Modellparameter und Toolaufrufe in die
 * jeweilige Provider-API (entspricht den Funktionen in {@code models/*_provider.py}).
 */
public interface ModelProvider {

    /** Stabiler Providername, z. B. {@code openai}. */
    String name();

    /** Erster Modellaufruf eines Turns. */
    ModelResponse createResponse(ModelRequest request);

    /** Folgeaufruf mit den Ergebnissen der zuvor angeforderten Toolaufrufe. */
    ModelResponse createFollowup(ModelRequest request, ModelResponse previous,
                                 List<ToolCall> toolCalls, List<Object> results);
}
