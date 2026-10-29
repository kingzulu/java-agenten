package com.zuluindustries.agenten.builder;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.agent.AgentRegistry;
import com.zuluindustries.agenten.agent.PermissionConfig;
import com.zuluindustries.agenten.agent.ToolConfig;
import com.zuluindustries.agenten.core.AgentPermissionException;
import com.zuluindustries.agenten.core.AgentPolicy;
import com.zuluindustries.agenten.core.AgentResult;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Deterministische Steuerung um prompt_agent und agent_creator_agent; entspricht
 * {@code agents/builder.py}.
 *
 * <p>Der Builder prüft seine eigene Schreibberechtigung, lässt beide Agenten ohne
 * Werkzeuge und Berechtigungen laufen und speichert das Ergebnis anschließend selbst
 * über {@link AgentDefinitionWriter}. Ein Modell kann so nie direkt schreiben.</p>
 */
public final class AgentBuilder {

    public static final String AGENT_ID = "agent_builder";
    public static final String PROMPT_AGENT = "prompt_agent";
    public static final String CREATOR_AGENT = "agent_creator_agent";

    /**
     * @param result          Ergebnis; bei Erfolg nennt {@code text} den Speicherort
     * @param generatedPrompt Prompt des prompt_agent oder {@code null}
     * @param savedPath       gespeicherte Datei oder {@code null} (Entwurf oder Fehler)
     * @param draft           unveränderte YAML-Ausgabe des agent_creator_agent
     */
    public record Result(AgentResult result, String generatedPrompt, Path savedPath, String draft) {
    }

    private final AgentRegistry agents;
    private final AgentPolicy policy;
    private final AgentDefinitionWriter writer;

    public AgentBuilder(AgentRegistry agents, AgentPolicy policy, AgentDefinitionWriter writer) {
        this.agents = agents;
        this.policy = policy;
        this.writer = writer;
    }

    /**
     * @param builder   Definition des agent_builder; liefert Modell- und Ausführungseinstellungen
     *                  und muss {@code write_agent_definition} verwenden dürfen
     * @param runner    führt einen Agenten aus
     * @param draftOnly nur den Entwurf erzeugen, nichts speichern
     */
    public Result run(AgentDefinition builder, String userInput, BiFunction<AgentDefinition, String, AgentResult> runner,
                      boolean draftOnly) {
        // Dieselben Berechtigungs- und Bestätigungsregeln wie in der Tool-Runtime.
        if (!draftOnly && !policy.effectiveAllowedTools(builder).contains(AgentDefinitionWriter.TOOL_NAME)) {
            throw new AgentPermissionException(AgentDefinitionWriter.TOOL_NAME + " ist für diesen Agenten gesperrt.");
        }
        List<String> confirmations = builder.permissions().requireConfirmationFor();
        if (!draftOnly && (confirmations.contains(AgentDefinitionWriter.TOOL_NAME) || confirmations.contains("write"))) {
            throw new AgentPermissionException(AgentDefinitionWriter.TOOL_NAME + " benötigt eine Bestätigung; "
                    + "ein Bestätigungsdialog ist noch nicht eingerichtet.");
        }
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("Die Eingabe ist leer.");
        }

        // Verschachtelte Agenten erben nie die Schreibberechtigung des Builders. Der
        // agent_creator_agent behält seine Instruktionen, nutzt aber Modell und
        // Ausführung des Builders.
        AgentDefinition prompt = withoutCapabilities(agents.get(PROMPT_AGENT, null), null);
        AgentDefinition creator = withoutCapabilities(agents.get(CREATOR_AGENT, null), builder);

        AgentCreator.Result created = AgentCreator.run(runner, prompt, creator, userInput);
        AgentResult result = created.result();
        if (result.failed() || draftOnly) {
            return new Result(result, created.generatedPrompt(), null, result.failed() ? null : result.text());
        }
        try {
            AgentDefinitionWriter.Saved saved = writer.write(result.text(), created.generatedPrompt());
            return new Result(withText(result, "Definition gespeichert: " + saved.path() + "\n"
                    + "Der neue Agent ist zunächst deaktiviert (enabled: false).", null),
                    created.generatedPrompt(), saved.path(), result.text());
        } catch (RuntimeException error) {
            String message = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
            return new Result(withText(result, "Definition konnte nicht gespeichert werden: " + message, message),
                    created.generatedPrompt(), null, result.text());
        }
    }

    private static AgentDefinition withoutCapabilities(AgentDefinition agent, AgentDefinition settingsFrom) {
        return new AgentDefinition(agent.id(), agent.name(), agent.version(), agent.enabled(), agent.description(),
                settingsFrom != null ? settingsFrom.model() : agent.model(), agent.capabilities(), List.of(),
                new ToolConfig(List.of(), List.of(), ToolConfig.MANUAL),
                settingsFrom != null ? settingsFrom.execution() : agent.execution(),
                new PermissionConfig(false, false, false, false, null),
                agent.guardrails(), agent.context(), agent.output());
    }

    private static AgentResult withText(AgentResult result, String text, String error) {
        return new AgentResult(result.traceId(), text, result.usage(), result.inputTokens(), result.outputTokens(),
                result.totalTokens(), error, result.provider(), result.model(), result.modelProfile());
    }
}
