package com.zuluindustries.agenten.core;

import java.util.List;

/** Verbrauch eines Agenten-Turns; entspricht {@code core/usage.py}. */
public record UsageRecord(
        String traceId,
        String provider,
        String model,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        double durationMs,
        List<ModelCall> modelCalls) {

    public UsageRecord {
        modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
    }
}
