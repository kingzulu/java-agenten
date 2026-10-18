package com.zuluindustries.agenten.core;

/**
 * Ergebnis eines Agentenlaufs.
 *
 * @param error gesetzt, wenn der Lauf fehlgeschlagen ist; {@code text} enthält dann
 *              eine Fehlermeldung der Form {@code Agentenfehler: ...}
 */
public record AgentResult(
        String traceId,
        String text,
        UsageRecord usage,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        String error,
        String provider,
        String model,
        String modelProfile) {

    public boolean failed() {
        return error != null;
    }

    public AgentResult withModelInfo(String newProvider, String newModel, String newProfile) {
        return new AgentResult(traceId, text, usage, inputTokens, outputTokens, totalTokens, error,
                newProvider, newModel, newProfile);
    }
}
