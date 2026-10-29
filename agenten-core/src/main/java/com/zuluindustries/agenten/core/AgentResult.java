package com.zuluindustries.agenten.core;

import java.util.ArrayList;
import java.util.List;

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

    /** Alle Modellaufrufe dieses Ergebnisses, auch die früherer Fallback-Versuche. */
    public List<ModelCall> modelCalls() {
        return usage == null ? List.of() : usage.modelCalls();
    }

    /**
     * Stellt die Aufrufe früherer, fehlgeschlagener Versuche voran, damit deren Tokens
     * und Kosten nach einem Fallback nicht verloren gehen.
     */
    public AgentResult withEarlierCalls(List<ModelCall> earlier) {
        if (earlier.isEmpty()) {
            return this;
        }
        List<ModelCall> calls = new ArrayList<>(earlier);
        calls.addAll(modelCalls());
        for (int i = 0; i < calls.size(); i++) {
            calls.set(i, calls.get(i).withCallIndex(i));
        }
        long in = calls.stream().mapToLong(ModelCall::inputTokens).sum();
        long out = calls.stream().mapToLong(ModelCall::outputTokens).sum();
        long total = calls.stream().mapToLong(ModelCall::totalTokens).sum();
        UsageRecord combined = new UsageRecord(traceId, provider, model, in, out, total,
                usage == null ? 0 : usage.durationMs(), calls);
        return new AgentResult(traceId, text, combined, in, out, total, error, provider, model, modelProfile);
    }

    public AgentResult withModelInfo(String newProvider, String newModel, String newProfile) {
        return new AgentResult(traceId, text, usage, inputTokens, outputTokens, totalTokens, error,
                newProvider, newModel, newProfile);
    }
}
