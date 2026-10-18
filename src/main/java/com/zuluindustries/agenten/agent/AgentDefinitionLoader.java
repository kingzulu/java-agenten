package com.zuluindustries.agenten.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zuluindustries.agenten.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Lädt YAML-Agentendefinitionen; entspricht {@code agents/loader.py}. */
public final class AgentDefinitionLoader {

    private AgentDefinitionLoader() {
    }

    public static AgentDefinition load(Path path) {
        JsonNode data;
        try {
            data = Json.DEFINITIONS_YAML.readTree(path.toFile());
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "Agentendefinition '" + path + "' konnte nicht gelesen werden: " + error.getMessage(), error);
        }
        if (data == null || data.isMissingNode() || data.isNull()) {
            data = Json.DEFINITIONS_YAML.createObjectNode();
        }
        if (!(data instanceof ObjectNode object)) {
            throw new IllegalArgumentException(
                    "Agentendefinition '" + path + "' muss ein YAML-Objekt enthalten.");
        }

        // ID aus Dateinamen ableiten, falls nicht gesetzt; Name aus ID ableiten.
        if (isBlank(object.get("id"))) {
            object.put("id", stem(path));
        }
        if (isBlank(object.get("name"))) {
            object.put("name", object.get("id").asText());
        }

        try {
            return Json.DEFINITIONS_YAML.treeToValue(object, AgentDefinition.class);
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "Ungültige Agentendefinition '" + path + "': " + rootMessage(error), error);
        }
    }

    /** Lädt alle aktivierten Definitionen eines Verzeichnisses, sortiert nach Dateiname. */
    public static Map<String, AgentDefinition> loadAll(Path directory) {
        Map<String, AgentDefinition> definitions = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            return definitions;
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> {
                String file = path.getFileName().toString();
                return file.endsWith(".yaml") || file.endsWith(".yml");
            }).sorted().forEach(paths::add);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        for (Path path : paths) {
            AgentDefinition definition = load(path);
            if (!definition.enabled()) {
                continue;
            }
            if (definitions.containsKey(definition.id())) {
                throw new IllegalArgumentException("Doppelte Agent-ID '" + definition.id() + "'.");
            }
            definitions.put(definition.id(), definition);
        }
        return definitions;
    }

    static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static boolean isBlank(JsonNode node) {
        return node == null || node.isNull() || node.asText().isBlank();
    }

    private static String stem(Path path) {
        String file = path.getFileName().toString();
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
}
