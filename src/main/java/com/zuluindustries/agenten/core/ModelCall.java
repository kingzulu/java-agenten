package com.zuluindustries.agenten.core;

import com.zuluindustries.agenten.model.ModelUsage;
import com.zuluindustries.agenten.model.TokenUsage;

/**
 * Ein einzelner, protokollierter Modellaufruf eines Agenten-Turns. Provider und Modell
 * werden je Aufruf festgehalten, damit Kosten auch nach einem Fallback stimmen.
 *
 * @param callType {@code initial}, {@code followup}, {@code iteration} oder {@code late_response}
 */
public record ModelCall(int callIndex, int step, String callType, String provider, String model,
                        long inputTokens, long outputTokens, long totalTokens,
                        long cachedInputTokens, long cacheWriteTokens) implements ModelUsage {

    public static ModelCall of(int callIndex, int step, String callType, String provider, String model,
                               TokenUsage usage) {
        return new ModelCall(callIndex, step, callType, provider, model,
                usage.inputTokens(), usage.outputTokens(), usage.totalTokens(),
                usage.cachedInputTokens(), usage.cacheWriteTokens());
    }

    @Override
    public TokenUsage usage() {
        return new TokenUsage(inputTokens, outputTokens, totalTokens, cachedInputTokens, cacheWriteTokens);
    }

    ModelCall withCallIndex(int index) {
        return new ModelCall(index, step, callType, provider, model, inputTokens, outputTokens, totalTokens,
                cachedInputTokens, cacheWriteTokens);
    }
}
