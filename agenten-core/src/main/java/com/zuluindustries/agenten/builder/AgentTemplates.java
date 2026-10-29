package com.zuluindustries.agenten.builder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prüft und speichert von einem Modell erzeugte Agentendefinitionen; entspricht
 * {@code agents/template_writer.py}.
 *
 * <p>Typische Formfehler der Modellausgabe (Markdown-Codeblock, {@code fallback} statt
 * {@code fallback_profile}, Guardrails als Liste, {@code retries} usw.) werden
 * korrigiert. Eine erzeugte Definition muss deaktiviert sein, eine ID in ASCII-snake_case
 * haben und den Prompt des prompt_agent unverändert als {@code description} enthalten.
 * Bestehende Dateien werden nie überschrieben.</p>
 */
public final class AgentTemplates {

    /** Ein geprüftes Template: der zu speichernde YAML-Text und die Definition. */
    public record Validated(String content, AgentDefinition definition) {
    }

    static final String OPENING = "<GENERATED_PROMPT>";
    static final String CLOSING = "</GENERATED_PROMPT>";

    private static final Pattern FENCE = Pattern.compile("```(?:yaml|yml)?\\s*\\n(?<yaml>.*)\\n```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern AGENT_ID = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Set<String> DISABLED_SCOPES = Set.of("none", "off", "disabled");

    /** YAML-Ausgabe mit Blockliteralen für mehrzeilige Texte wie die description. */
    static final ObjectMapper WRITER = Json.DEFINITIONS_YAML.copyWith(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
            .build());

    private AgentTemplates() {
    }

    public static Validated validate(String text, String expectedPrompt) {
        String content = stripMarkdownFence(text);
        JsonNode data;
        try {
            data = Json.DEFINITIONS_YAML.readTree(content);
        } catch (IOException error) {
            throw new IllegalArgumentException("Das erzeugte Agenten-YAML ist ungültig: " + error.getMessage(), error);
        }
        if (!(data instanceof ObjectNode object)) {
            throw new IllegalArgumentException("Das erzeugte Agenten-YAML muss ein YAML-Objekt enthalten.");
        }
        if (normalize(object)) {
            content = write(object).stripTrailing();
        }

        AgentDefinition definition;
        try {
            definition = Json.DEFINITIONS_YAML.treeToValue(object, AgentDefinition.class);
        } catch (IOException | IllegalArgumentException error) {
            Throwable cause = error;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw new IllegalArgumentException("Die erzeugte Agentendefinition ist ungültig: " + cause.getMessage(),
                    error);
        }
        if (definition.enabled()) {
            throw new IllegalArgumentException("Eine erzeugte Agentendefinition muss deaktiviert sein.");
        }
        if (definition.id() == null || !AGENT_ID.matcher(definition.id()).matches()) {
            throw new IllegalArgumentException("Die Agenten-ID muss ASCII-snake_case verwenden.");
        }
        if (!definition.description().strip().equals(expectedPrompt.strip())) {
            throw new IllegalArgumentException(
                    "Die description stimmt nicht unverändert mit dem erzeugten Prompt überein.");
        }
        return new Validated(content, definition);
    }

    /** Prüft und speichert als {@code <id>.yaml}; eine vorhandene Datei wird nie überschrieben. */
    public static Path save(String text, String expectedPrompt, Path definitionsDir) {
        Validated validated = validate(text, expectedPrompt);
        Path destination = definitionsDir.resolve(validated.definition().id() + ".yaml");
        try {
            Files.createDirectories(definitionsDir);
            // CREATE_NEW lehnt auch symbolische Links und gleichzeitige Erzeuger ab.
            try (Writer writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writer.write(validated.content().stripTrailing() + "\n");
            }
        } catch (FileAlreadyExistsException error) {
            throw new IllegalArgumentException("Die Agentendefinition '" + destination.getFileName()
                    + "' existiert bereits und wird nicht überschrieben.", error);
        } catch (IOException error) {
            throw new UncheckedIOException("Agentendefinition konnte nicht gespeichert werden: " + destination, error);
        }
        return destination;
    }

    /** YAML-Darstellung einer vollständigen Definition einschließlich aller Standardwerte. */
    public static String canonical(AgentDefinition definition) {
        return write(definition);
    }

    static String stripMarkdownFence(String text) {
        String content = text.strip();
        Matcher match = FENCE.matcher(content);
        return match.matches() ? match.group("yaml").strip() : content;
    }

    /** Korrigiert bekannte Formfehler; liefert {@code true}, wenn sich etwas geändert hat. */
    static boolean normalize(ObjectNode data) {
        boolean changed = false;

        if (data.get("description") instanceof TextNode description) {
            String stripped = description.asText().strip();
            String cleaned = stripped;
            if (cleaned.startsWith(OPENING)) {
                cleaned = cleaned.substring(OPENING.length()).strip();
            }
            if (cleaned.endsWith(CLOSING)) {
                cleaned = cleaned.substring(0, cleaned.length() - CLOSING.length()).strip();
            }
            if (!cleaned.equals(stripped)) {
                data.put("description", cleaned);
                changed = true;
            }
        }

        if (data.get("model") instanceof ObjectNode model) {
            if (model.has("fallback")) {
                if (!model.has("fallback_profile")) {
                    model.set("fallback_profile", model.get("fallback"));
                }
                model.remove("fallback");
                changed = true;
            }
            if (model.get("fallback_profile") instanceof ObjectNode fallback) {
                // Die Runtime kennt nur einen Profilverweis, keine eigenen Fallback-Einstellungen.
                List<JsonNode> references = new ArrayList<>();
                for (String key : List.of("name", "profile")) {
                    if (fallback.has(key)) {
                        references.add(fallback.get(key));
                    }
                }
                if (references.isEmpty()
                        || references.stream().anyMatch(value -> !value.isTextual() || value.asText().isBlank())
                        || references.stream().map(JsonNode::asText).distinct().count() != 1) {
                    throw new IllegalArgumentException("fallback_profile muss einen eindeutigen Profilnamen enthalten.");
                }
                model.set("fallback_profile", references.getFirst());
                changed = true;
            }
        }

        if (data.get("guardrails") instanceof ArrayNode list) {
            ObjectNode guardrails = data.putObject("guardrails");
            guardrails.putArray("forbidden_actions");
            guardrails.set("instructions", list);
            changed = true;
        }
        if (data.get("guardrails") instanceof ObjectNode guardrails) {
            for (String field : List.of("instructions", "forbidden_actions")) {
                if (!(guardrails.get(field) instanceof ArrayNode entries)) {
                    continue;
                }
                for (int i = 0; i < entries.size(); i++) {
                    // YAML liest "Satz: Fortsetzung" als Zuordnung. Nur ein einzelnes
                    // Text/Text-Paar lässt sich verlustfrei zurückgewinnen.
                    if (entries.get(i) instanceof ObjectNode entry && entry.size() == 1) {
                        Map.Entry<String, JsonNode> pair = entry.properties().iterator().next();
                        if (pair.getValue().isTextual()) {
                            entries.set(i, TextNode.valueOf(pair.getKey() + ": " + pair.getValue().asText()));
                            changed = true;
                        }
                    }
                }
            }
        }

        JsonNode context = data.get("context");
        if (context instanceof ArrayNode list) {
            List<String> lines = new ArrayList<>();
            for (JsonNode item : list) {
                lines.add(item.isTextual() ? item.asText() : item.toString());
            }
            ObjectNode replacement = data.putObject("context");
            replacement.put("system_context", String.join("\n", lines));
            replacement.putArray("knowledge_sources");
            replacement.putArray("rag_sources");
            ObjectNode memory = replacement.putObject("memory");
            memory.put("enabled", false);
            memory.put("scope", "session");
            changed = true;
        } else if (context instanceof ObjectNode object && object.get("memory") != null
                && object.get("memory").isBoolean()) {
            boolean enabled = object.get("memory").asBoolean();
            ObjectNode memory = object.putObject("memory");
            memory.put("enabled", enabled);
            memory.put("scope", "session");
            changed = true;
        } else if (context instanceof ObjectNode object && object.get("memory") instanceof ObjectNode memory) {
            JsonNode scope = memory.get("scope");
            if (scope == null || scope.isNull() || (scope.isTextual() && DISABLED_SCOPES.contains(scope.asText()))) {
                memory.put("scope", "session");
                changed = true;
            }
        }

        if (data.get("execution") instanceof ObjectNode execution) {
            if (execution.has("retries")) {
                if (!execution.has("max_retries")) {
                    execution.set("max_retries", execution.get("retries"));
                }
                execution.remove("retries");
                changed = true;
            }
            if (execution.has("output")) {
                JsonNode executionOutput = execution.get("output");
                JsonNode output = data.has("output") ? data.get("output") : data.putObject("output");
                if (output instanceof ObjectNode object && isFormat(executionOutput) && !isFormat(object.get("format"))) {
                    object.set("format", executionOutput);
                }
                execution.remove("output");
                changed = true;
            }
            if (execution.has("output_format")) {
                JsonNode output = data.has("output") ? data.get("output") : data.putObject("output");
                if (output instanceof ObjectNode object && !truthy(object.get("format"))) {
                    object.set("format", execution.get("output_format"));
                }
                execution.remove("output_format");
                changed = true;
            }
        }

        if (data.get("output") instanceof ObjectNode output) {
            if (output.has("type")) {
                if (!output.has("format")) {
                    output.set("format", output.get("type"));
                }
                output.remove("type");
                changed = true;
            }
            JsonNode format = output.get("format");
            if (format != null && !format.isNull() && !isFormat(format)) {
                output.put("format", "text");
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isFormat(JsonNode value) {
        return value != null && value.isTextual() && Set.of("text", "json").contains(value.asText());
    }

    private static boolean truthy(JsonNode value) {
        return value != null && !value.isNull() && !(value.isTextual() && value.asText().isEmpty());
    }

    private static String write(Object value) {
        try {
            return WRITER.writeValueAsString(value);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
