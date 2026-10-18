package com.zuluindustries.agenten.model;

/** Tokenverbrauch eines einzelnen Modellaufrufs. */
public record TokenUsage(long inputTokens, long outputTokens, long totalTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    public static TokenUsage of(long inputTokens, long outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens);
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                totalTokens + other.totalTokens);
    }
}
