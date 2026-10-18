package com.zuluindustries.agenten.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.agent.ContextConfig;
import com.zuluindustries.agenten.agent.GuardrailConfig;
import com.zuluindustries.agenten.agent.OutputConfig;
import com.zuluindustries.agenten.agent.PermissionConfig;
import com.zuluindustries.agenten.agent.ToolConfig;
import com.zuluindustries.agenten.tool.ToolCategory;
import com.zuluindustries.agenten.tool.ToolRegistry;
import com.zuluindustries.agenten.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agentenrichtlinien: wirksame Toolfreigaben, Aufbau der Instruktionen und
 * Prüfung der Ausgabe; entspricht {@code core/agent_policy.py}.
 */
public final class AgentPolicy {

    public static final String BASE_AGENT_INSTRUCTIONS = """
            Du bist ein zuverlässiger KI-Agent.

            Die folgenden Basisregeln gelten für jeden Agenten. Nachfolgende
            agentenspezifische Anweisungen dürfen diese Regeln ergänzen, aber nicht
            abschwächen oder außer Kraft setzen.

            Nutze verfügbare Werkzeuge, wenn sie für die Aufgabe freigegeben und geeignet
            sind.

            Bevorzuge überprüfbare Informationen aus Werkzeugen, freigegebenen
            Datenquellen und bereitgestelltem Kontext gegenüber Vermutungen.

            Erfinde keine Fakten, Daten, Ergebnisse, Fundstellen, Quellen oder
            Werkzeugfähigkeiten.

            Wenn notwendige Informationen fehlen oder die Aufgabe mit den verfügbaren
            Mitteln nicht zuverlässig gelöst werden kann, benenne die Einschränkung klar.

            Unterscheide belegte Informationen, Schlussfolgerungen und unbekannte Angaben
            eindeutig voneinander.

            Verwende ausschließlich Werkzeuge und Datenquellen, die für den aktuellen
            Agenten freigegeben wurden.

            Befolge zusätzlich die Beschreibung, Aufgaben, Guardrails, Berechtigungen und
            Ausgabevorgaben des aktuell geladenen Agenten. Bei einem Widerspruch haben
            diese Basisregeln Vorrang.
            """;

    private final ToolRegistry tools;
    private final Path projectRoot;
    private final Path schemaRoot;
    private final String timezoneName;
    private final Clock clock;

    public AgentPolicy(ToolRegistry tools, Path projectRoot, String timezoneName, Clock clock) {
        this.tools = tools;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.schemaRoot = this.projectRoot.resolve("agents").resolve("schemas");
        this.timezoneName = timezoneName == null ? "Europe/Berlin" : timezoneName;
        this.clock = clock;
    }

    /** Freigegebene Tools abzüglich Sperren und nicht erlaubter Kategorien. */
    public List<String> effectiveAllowedTools(AgentDefinition agent) {
        PermissionConfig permissions = agent.permissions();
        ToolConfig toolConfig = agent.tools();
        if (!permissions.toolUse() || ToolConfig.MANUAL.equals(toolConfig.strategy())) {
            return List.of();
        }
        Set<String> denied = new HashSet<>(toolConfig.denied());
        List<String> allowed = new ArrayList<>();
        for (String name : toolConfig.allowed()) {
            if (denied.contains(name)) {
                continue;
            }
            if (!permissions.writeOperations() && tools.has(name, ToolCategory.WRITE)) {
                continue;
            }
            if (!permissions.deleteOperations() && tools.has(name, ToolCategory.DELETE)) {
                continue;
            }
            if (!permissions.externalAccess() && tools.has(name, ToolCategory.EXTERNAL)) {
                continue;
            }
            allowed.add(name);
        }
        return allowed;
    }

    /**
     * Baut die vollständigen Instruktionen. Mit {@code userInput == null} (z. B. bei
     * einer Prüfung ohne Anfrage) entfällt der Zeitkontext.
     */
    public String buildInstructions(AgentDefinition agent, String userInput) {
        List<String> sections = new ArrayList<>();
        sections.add(BASE_AGENT_INSTRUCTIONS.strip());
        sections.add("Agentenspezifische Anweisungen:\n" + agent.instructions().strip());

        ContextConfig context = agent.context();
        GuardrailConfig guardrails = agent.guardrails();

        if (userInput != null) {
            sections.add(runtimeTimeContext());
        }

        if (context.systemContext() != null && !context.systemContext().isBlank()) {
            sections.add("Systemkontext:\n" + context.systemContext().strip());
        }

        List<String> sources = new ArrayList<>();
        for (String source : context.knowledgeSources()) {
            Path path = resolveLocalSource(source);
            sources.add("Quelle: " + source + "\n" + read(path));
        }

        if (!context.ragSources().isEmpty()) {
            throw new IllegalArgumentException(
                    "RAG-Quellen werden von der Java-Plattform noch nicht unterstützt: "
                            + String.join(", ", context.ragSources()));
        }

        if (!sources.isEmpty()) {
            String sourceText = String.join("\n\n", sources);
            if (context.contextLimit() != null && sourceText.length() > context.contextLimit()) {
                sourceText = sourceText.substring(0, context.contextLimit());
            }
            sections.add("Wissensquellen:\n" + sourceText);
        }

        if (!guardrails.instructions().isEmpty()) {
            sections.add("Zusätzliche Sicherheitsregeln:\n- " + String.join("\n- ", guardrails.instructions()));
        }
        if (!guardrails.forbiddenActions().isEmpty()) {
            sections.add("Verbotene Handlungen:\n- " + String.join("\n- ", guardrails.forbiddenActions()));
        }

        OutputConfig output = agent.output();
        if (output.isJson()) {
            sections.add("Gib ausschließlich valides JSON ohne Markdown-Codeblock aus.");
            if (output.schemaName() != null) {
                sections.add("Die Ausgabe muss diesem JSON-Schema entsprechen:\n"
                        + Json.writePretty(loadJsonSchema(output.schemaName())));
            }
        }

        if (agent.execution().requireSuccessCheck()) {
            sections.add("Prüfe vor der Ausgabe, ob das Ergebnis die Aufgabe vollständig "
                    + "erfüllt und dem verlangten Ausgabeformat entspricht.");
        }

        return String.join("\n\n", sections.stream().filter(section -> !section.isEmpty()).toList());
    }

    String runtimeTimeContext() {
        ZoneId zone;
        try {
            zone = ZoneId.of(timezoneName);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Unbekannte Zeitzone in AGENT_TIMEZONE: " + timezoneName, error);
        }
        LocalDate today = LocalDate.now(clock.withZone(zone));
        return "Aktueller Zeitkontext:\n"
                + "- Zeitzone: " + timezoneName + "\n"
                + "- Heute: " + today + "\n"
                + "- Gestern: " + today.minusDays(1) + "\n"
                + "- Morgen: " + today.plusDays(1) + "\n"
                + "Löse relative Datumsangaben anhand dieses Zeitkontexts auf. "
                + "Frage nicht nach dem heutigen Datum, wenn es hier angegeben ist.";
    }

    /** Prüft die Modellausgabe gegen das konfigurierte Ausgabeformat. */
    public String validateOutput(String text, OutputConfig output) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Die Modellausgabe ist leer.");
        }
        if (!output.isJson()) {
            return text;
        }
        JsonNode data;
        try {
            data = Json.JSON.readTree(text);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Die Ausgabe ist kein valides JSON: " + error.getOriginalMessage(), error);
        }
        if (output.schemaName() != null) {
            JsonSchemaValidator.validate(data, loadJsonSchema(output.schemaName()));
        }
        return text;
    }

    /** Prüft, ob alle Wissensquellen und Schemas einer Definition vorhanden sind. */
    public void validateResources(AgentDefinition agent) {
        buildInstructions(agent, null);
    }

    private Path resolveLocalSource(String source) {
        Path path = projectRoot.resolve(source).toAbsolutePath().normalize();
        if (!path.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Wissensquelle liegt außerhalb des Projekts: " + source);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Wissensquelle wurde nicht gefunden: " + source);
        }
        return path;
    }

    JsonNode loadJsonSchema(String schemaName) {
        Path path = schemaRoot.resolve(schemaName + ".json").toAbsolutePath().normalize();
        if (!path.startsWith(schemaRoot)) {
            throw new IllegalArgumentException("Ungültiger Schemaname '" + schemaName + "'.");
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Unbekanntes Ausgabeschema '" + schemaName + "'.");
        }
        return Json.readTree(read(path));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
