package com.zuluindustries.agenten.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.zuluindustries.agenten.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prüft und speichert vom workflow_creator_agent erzeugte Workflow-Gerüste; entspricht
 * {@code workflows/template_writer.py}.
 *
 * <p>Ein Markdown-Codeblock wird entfernt, eine Schrittliste mit {@code id} wird in die
 * Objektform umgewandelt und ein fehlender technischer Endschritt ergänzt. Das Gerüst muss
 * deaktiviert sein, weil seine Platzhalter-Actions erst noch umgesetzt werden müssen.
 * Bestehende Dateien werden nie überschrieben.</p>
 */
public final class WorkflowTemplates {

    /** Ein geprüftes Gerüst: der zu speichernde YAML-Text und die Definition. */
    public record Validated(String content, WorkflowDefinition definition) {
    }

    private static final Pattern FENCE = Pattern.compile("```(?:yaml|yml)?\\s*\\n(?<yaml>.*)\\n```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STEP_ID = Pattern.compile("[a-z][a-z0-9_]*");
    private static final String FALLBACK_KEY = "workflow_template";

    private static final ObjectMapper WRITER = Json.DEFINITIONS_YAML.copyWith(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build());

    private WorkflowTemplates() {
    }

    public static Validated validate(String text) {
        String content = stripMarkdownFence(text);
        JsonNode data;
        try {
            data = Json.DEFINITIONS_YAML.readTree(content);
        } catch (IOException error) {
            throw new IllegalArgumentException("Das erzeugte Workflow-YAML ist ungültig: " + error.getMessage(), error);
        }
        if (!(data instanceof ObjectNode object)) {
            throw new IllegalArgumentException("Das erzeugte Workflow-YAML muss ein YAML-Objekt enthalten.");
        }

        boolean normalized = normalizeStepList(object);
        boolean completed = addMissingEndStep(object);
        if (normalized || completed) {
            content = write(object).stripTrailing();
        }

        WorkflowDefinition definition;
        try {
            definition = Json.DEFINITIONS_YAML.treeToValue(object, WorkflowDefinition.class);
        } catch (IOException | IllegalArgumentException error) {
            Throwable cause = error;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            throw new IllegalArgumentException("Das erzeugte Workflow-Gerüst ist ungültig: " + cause.getMessage(),
                    error);
        }
        if (definition.enabled()) {
            throw new IllegalArgumentException("Ein erzeugtes Workflow-Gerüst muss deaktiviert sein.");
        }
        return new Validated(content, definition);
    }

    /**
     * Prüft und speichert als {@code <name>.yaml}; bei vorhandener Datei mit Zähler
     * ({@code _2}, {@code _3}, ...), nie überschreibend.
     */
    public static Path save(String text, Path definitionsDir) {
        Validated validated = validate(text);
        String key = safeKey(validated.definition().name());
        try {
            Files.createDirectories(definitionsDir);
            for (int suffix = 1; ; suffix++) {
                Path destination = definitionsDir.resolve(suffix == 1 ? key + ".yaml" : key + "_" + suffix + ".yaml");
                // CREATE_NEW lehnt auch symbolische Links und gleichzeitige Erzeuger ab.
                try (Writer writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    writer.write(validated.content().stripTrailing() + "\n");
                    return destination;
                } catch (FileAlreadyExistsException error) {
                    // nächsten freien Namen versuchen
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Workflow-Gerüst konnte nicht gespeichert werden.", error);
        }
    }

    /** Dateiname aus dem Workflow-Namen: ASCII-snake_case, höchstens 64 Zeichen. */
    static String safeKey(String name) {
        String lower = name.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        String ascii = Normalizer.normalize(lower, Normalizer.Form.NFKD).replaceAll("[^\\x00-\\x7F]", "");
        String key = ascii.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (key.length() > 64) {
            key = key.substring(0, 64).replaceAll("_+$", "");
        }
        return key.isEmpty() ? FALLBACK_KEY : key;
    }

    static String stripMarkdownFence(String text) {
        String content = text.strip();
        Matcher match = FENCE.matcher(content);
        return match.matches() ? match.group("yaml").strip() : content;
    }

    /** Wandelt {@code steps} als Liste mit {@code id} in die Objektform um. */
    private static boolean normalizeStepList(ObjectNode data) {
        if (!(data.get("steps") instanceof ArrayNode steps)) {
            return false;
        }
        ObjectNode normalized = data.objectNode();
        int position = 0;
        for (JsonNode item : steps) {
            position++;
            if (!(item instanceof ObjectNode step)) {
                throw new IllegalArgumentException("Workflow-Schritt " + position + " muss ein YAML-Objekt sein.");
            }
            ObjectNode copy = step.deepCopy();
            JsonNode id = copy.remove("id");
            if (id == null || !id.isTextual() || !STEP_ID.matcher(id.asText()).matches()) {
                throw new IllegalArgumentException("Workflow-Schritt " + position
                        + " benötigt eine eindeutige ASCII-snake_case-ID.");
            }
            if (normalized.has(id.asText())) {
                throw new IllegalArgumentException("Workflow-Schritt '" + id.asText() + "' ist doppelt vorhanden.");
            }
            normalized.set(id.asText(), copy);
        }
        data.set("steps", normalized);
        return true;
    }

    /**
     * Ergänzt nach dem einzigen Schritt ohne Nachfolger einen technischen Endschritt,
     * sofern es noch keinen oder genau einen gibt, und erhöht {@code limits.max_steps}
     * bei Bedarf auf die Schrittzahl.
     */
    private static boolean addMissingEndStep(ObjectNode data) {
        if (!(data.get("steps") instanceof ObjectNode steps) || steps.isEmpty()) {
            return false;
        }
        List<String> endSteps = new ArrayList<>();
        List<String> dangling = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : steps.properties()) {
            if (!(entry.getValue() instanceof ObjectNode step)) {
                continue;
            }
            String kind = step.path("kind").asText(null);
            if ("end".equals(kind)) {
                endSteps.add(entry.getKey());
            } else if (!"condition".equals(kind) && (step.get("next") == null || step.get("next").isNull())) {
                dangling.add(entry.getKey());
            }
        }
        if (dangling.size() != 1 || endSteps.size() > 1) {
            return false;
        }

        String endKey;
        if (endSteps.size() == 1) {
            endKey = endSteps.getFirst();
        } else {
            endKey = "complete";
            for (int suffix = 2; steps.has(endKey); suffix++) {
                endKey = "complete_" + suffix;
            }
            steps.putObject(endKey).put("kind", "end");
        }
        ((ObjectNode) steps.get(dangling.getFirst())).put("next", endKey);
        ObjectNode limits = data.get("limits") instanceof ObjectNode existing ? existing : data.putObject("limits");
        JsonNode configured = limits.get("max_steps");
        int maxSteps = configured != null && configured.isInt() ? configured.asInt() : 0;
        limits.put("max_steps", Math.max(maxSteps, steps.size()));
        return true;
    }

    private static String write(Object value) {
        try {
            return WRITER.writeValueAsString(value);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
