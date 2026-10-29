package com.zuluindustries.agenten.builder;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.core.AgentPolicy;
import com.zuluindustries.agenten.core.Capabilities;
import com.zuluindustries.agenten.model.ModelProfiles;
import com.zuluindustries.agenten.tool.FunctionTool;
import com.zuluindustries.agenten.tool.ToolCategory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Das eng begrenzte Schreibwerkzeug {@code write_agent_definition}; entspricht
 * {@code tools/agent_definitions.py}: nur geprüfte, deaktivierte Definitionen,
 * festes Verzeichnis, kein Überschreiben.
 *
 * <p>Neben der Form prüft es, ob Modellprofile, Tools, Skills, Wissensquellen und
 * Ausgabeschemas existieren, und speichert die Definition vollständig mit allen
 * Standardwerten.</p>
 */
public final class AgentDefinitionWriter {

    public static final String TOOL_NAME = "write_agent_definition";

    /** Abhängigkeiten, die erst nach dem Aufbau der Plattform bereitstehen. */
    public record Checks(ModelProfiles profiles, Capabilities capabilities, AgentPolicy policy) {
    }

    /** Ergebnis eines Schreibvorgangs. */
    public record Saved(Path path, String agentId, boolean enabled) {

        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", path.toString());
            data.put("agent_id", agentId);
            data.put("enabled", enabled);
            return data;
        }
    }

    private final Path definitionsDir;
    private final Supplier<Checks> checks;

    /**
     * @param definitionsDir Zielverzeichnis, üblicherweise {@code agents/definitions}
     * @param checks         liefert die Prüfungen; wird erst beim Schreiben abgefragt
     */
    public AgentDefinitionWriter(Path definitionsDir, Supplier<Checks> checks) {
        this.definitionsDir = definitionsDir;
        this.checks = checks;
    }

    public Saved write(String text, String expectedPrompt) {
        if (expectedPrompt == null || expectedPrompt.isBlank()) {
            throw new IllegalArgumentException("Der vom prompt_agent erzeugte Prompt ist leer.");
        }
        AgentDefinition definition = AgentTemplates.validate(text, expectedPrompt).definition();
        Checks current = checks.get();
        for (String profile : new String[] {definition.model().profile(), definition.model().fallbackProfile()}) {
            if (profile != null && !current.profiles().contains(profile)) {
                throw new IllegalArgumentException("Unbekanntes Modellprofil '" + profile + "'.");
            }
        }
        current.capabilities().prepare(definition.tools().allowed(), definition.skills());
        // Dieselben Quellen-, Pfad- und Schemaprüfungen wie vor einem Modellaufruf.
        current.policy().validateResources(definition);
        if (definition.name() == null || definition.name().isBlank()) {
            definition = definition.withIdentity(definition.id(), definition.id());
        }
        // Standardwerte mitspeichern, z. B. model.temperature und execution.max_iterations.
        Path path = AgentTemplates.save(AgentTemplates.canonical(definition), expectedPrompt, definitionsDir);
        return new Saved(path, definition.id(), definition.enabled());
    }

    /** Das Werkzeug für die Tool-Registry, Kategorie {@link ToolCategory#WRITE}. */
    public FunctionTool tool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("text", Map.of("type", "string", "description", "Vollständige Agentendefinition als YAML"));
        properties.put("expected_prompt", Map.of("type", "string", "description", "Unveränderter Prompt des prompt_agent"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("text", "expected_prompt"));
        parameters.put("additionalProperties", false);
        return FunctionTool.of(TOOL_NAME,
                "Validiert und speichert eine neue deaktivierte Agentendefinition ohne Überschreiben.",
                parameters,
                arguments -> write(String.valueOf(arguments.get("text")),
                        String.valueOf(arguments.get("expected_prompt"))).toMap(),
                ToolCategory.WRITE);
    }
}
