package com.zuluindustries.agenten.model;

/**
 * Tokenverbrauch eines einzelnen Modellaufrufs.
 *
 * @param inputTokens       alle Input-Tokens, einschließlich Cache-Treffern und Cache-Schreibvorgängen
 * @param cachedInputTokens Anteil der Input-Tokens, der aus dem Prompt-Cache gelesen wurde
 * @param cacheWriteTokens  Anteil der Input-Tokens, der in den Prompt-Cache geschrieben wurde
 */
public record TokenUsage(long inputTokens, long outputTokens, long totalTokens,
                         long cachedInputTokens, long cacheWriteTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    public TokenUsage {
        if (cachedInputTokens < 0 || cacheWriteTokens < 0 || cachedInputTokens + cacheWriteTokens > inputTokens) {
            throw new IllegalArgumentException("Ungültige Cache-Tokenzahlen: input=" + inputTokens
                    + " cached=" + cachedInputTokens + " cache_write=" + cacheWriteTokens);
        }
    }

    public TokenUsage(long inputTokens, long outputTokens, long totalTokens) {
        this(inputTokens, outputTokens, totalTokens, 0, 0);
    }

    public static TokenUsage of(long inputTokens, long outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens);
    }

    /** Input-Tokens zum normalen Preis, also ohne Cache-Treffer und Cache-Schreibvorgänge. */
    public long uncachedInputTokens() {
        return inputTokens - cachedInputTokens - cacheWriteTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                totalTokens + other.totalTokens, cachedInputTokens + other.cachedInputTokens,
                cacheWriteTokens + other.cacheWriteTokens);
    }
}
