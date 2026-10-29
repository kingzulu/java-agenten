package com.zuluindustries.agenten.workflow;

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

/** Lädt YAML-Workflows; der Schlüssel ist der Dateiname ohne Endung. */
public final class WorkflowLoader {

    private WorkflowLoader() {
    }

    public static WorkflowDefinition load(Path path) {
        try {
            return parse(Files.readString(path), path.toString());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Liest einen Workflow aus YAML-Text; {@code source} erscheint in Fehlermeldungen. */
    public static WorkflowDefinition parse(String yaml, String source) {
        try {
            return Json.DEFINITIONS_YAML.readValue(yaml, WorkflowDefinition.class);
        } catch (IOException | IllegalArgumentException error) {
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            throw new IllegalArgumentException("Ungültiger Workflow '" + source + "': " + root.getMessage(), error);
        }
    }

    public static Map<String, WorkflowDefinition> loadAll(Path directory) {
        Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            return definitions;
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".yaml")).sorted().forEach(paths::add);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        for (Path path : paths) {
            String file = path.getFileName().toString();
            String key = file.substring(0, file.length() - ".yaml".length());
            if (definitions.containsKey(key)) {
                throw new IllegalArgumentException("Workflow doppelt definiert: " + key);
            }
            definitions.put(key, load(path));
        }
        return definitions;
    }
}
