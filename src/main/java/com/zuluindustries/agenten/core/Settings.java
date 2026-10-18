package com.zuluindustries.agenten.core;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Betriebskonfiguration aus Umgebungsvariablen; entspricht {@code core/settings.py}.
 *
 * @param projectRoot         Wurzelverzeichnis mit {@code agents/}, {@code skills/} und {@code workflows/}
 *                            ({@code AGENTEN_HOME}, Standard: aktuelles Verzeichnis)
 * @param maxAgentSteps       Standardgrenze der Toolschritte ({@code MAX_AGENT_STEPS}, Standard 5)
 * @param defaultModelProfile Modellprofil ohne Agentenangabe ({@code DEFAULT_MODEL_PROFILE})
 * @param agentTimezone       Zeitzone des Zeitkontexts ({@code AGENT_TIMEZONE})
 * @param logLevel            Loglevel ({@code LOG_LEVEL})
 */
public record Settings(Path projectRoot, int maxAgentSteps, String defaultModelProfile, String agentTimezone,
                       String logLevel) {

    public static Settings load(Function<String, String> environment) {
        String home = environment.apply("AGENTEN_HOME");
        String steps = environment.apply("MAX_AGENT_STEPS");
        int maxSteps;
        try {
            maxSteps = steps == null || steps.isBlank() ? 5 : Integer.parseInt(steps.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("MAX_AGENT_STEPS muss eine ganze Zahl sein.", error);
        }
        return new Settings(
                Path.of(home == null || home.isBlank() ? "." : home).toAbsolutePath().normalize(),
                maxSteps,
                valueOr(environment.apply("DEFAULT_MODEL_PROFILE"), "openai-mini"),
                valueOr(environment.apply("AGENT_TIMEZONE"), "Europe/Berlin"),
                valueOr(environment.apply("LOG_LEVEL"), "INFO").toUpperCase(java.util.Locale.ROOT));
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
