package com.zuluindustries.agenten.agent;

/** Modellparameter eines Agenten. */
public record ModelConfig(
        String profile,
        Double temperature,
        Integer maxOutputTokens,
        String fallbackProfile,
        Boolean store) {

    public ModelConfig {
        profile = profile == null ? "openai-mini" : profile;
        temperature = temperature == null ? 0.2 : temperature;
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("model.temperature muss zwischen 0.0 und 2.0 liegen.");
        }
        maxOutputTokens = Validation.atLeast("model.max_output_tokens", maxOutputTokens, 4096, 1);
    }

    public static ModelConfig defaults() {
        return new ModelConfig(null, null, null, null, null);
    }
}
