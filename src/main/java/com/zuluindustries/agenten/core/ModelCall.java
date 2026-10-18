package com.zuluindustries.agenten.core;

import com.zuluindustries.agenten.model.TokenUsage;

/**
 * Ein einzelner, protokollierter Modellaufruf eines Agenten-Turns.
 *
 * @param callType {@code initial}, {@code followup}, {@code iteration} oder {@code late_response}
 */
public record ModelCall(int callIndex, int step, String callType,
                        long inputTokens, long outputTokens, long totalTokens) {

    public static ModelCall of(int callIndex, int step, String callType, TokenUsage usage) {
        return new ModelCall(callIndex, step, callType,
                usage.inputTokens(), usage.outputTokens(), usage.totalTokens());
    }
}
