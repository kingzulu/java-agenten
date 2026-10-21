package com.zuluindustries.agenten.tool.workspace;

import com.zuluindustries.agenten.tool.FunctionTool;
import com.zuluindustries.agenten.tool.Tool;
import com.zuluindustries.agenten.tool.ToolCategory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Datei-Tools des Code-Schwarms: Auflisten, Lesen und Schreiben im Arbeitsverzeichnis.
 *
 * <p>Ein Löschtool gibt es bewusst nicht. Fehler, die das Modell selbst beheben kann
 * (Datei fehlt, Pfad unzulässig), werden als {@code error} zurückgegeben statt
 * geworfen, damit der Agent weiterarbeiten kann. Ohne konfiguriertes
 * Arbeitsverzeichnis bricht jeder Aufruf ab.</p>
 */
public final class WorkspaceTools {

    /** Höchstens so viele Zeichen liefert {@code read_file}. */
    static final int MAX_READ_CHARS = 60_000;
    /** Höchstens so viele Zeichen schreibt {@code write_file}. */
    static final int MAX_WRITE_CHARS = 200_000;
    /** Höchstens so viele Einträge liefert {@code list_files}. */
    static final int MAX_LIST_ENTRIES = 500;

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "target", "build", "node_modules", ".idea");

    private final Workspace workspace;

    /** @param workspace Arbeitsverzeichnis oder {@code null}, wenn {@code SCHWARM_WORKSPACE} fehlt */
    public WorkspaceTools(Workspace workspace) {
        this.workspace = workspace;
    }

    public List<Tool> tools() {
        return List.of(listFilesTool(), readFileTool(), writeFileTool());
    }

    public Map<String, Object> listFiles(String directory) {
        return guarded(() -> {
            Path start = directory == null || directory.isBlank() ? workspace().root() : workspace().resolve(directory);
            if (!Files.isDirectory(start)) {
                return error("Verzeichnis nicht gefunden: " + directory);
            }
            List<String> files = new ArrayList<>();
            boolean truncated;
            try (Stream<Path> walk = Files.walk(start)) {
                List<Path> all = walk
                        .filter(path -> !path.equals(start))
                        .filter(path -> !skipped(path))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .limit(MAX_LIST_ENTRIES + 1L)
                        .toList();
                truncated = all.size() > MAX_LIST_ENTRIES;
                all.stream().limit(MAX_LIST_ENTRIES).forEach(path -> files.add(workspace().relative(path)));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("files", files);
            result.put("truncated", truncated);
            return result;
        });
    }

    public Map<String, Object> readFile(String path) {
        return guarded(() -> {
            Path file = workspace().resolve(path);
            if (!Files.isRegularFile(file)) {
                return error("Datei nicht gefunden: " + path);
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", workspace().relative(file));
            result.put("content", content.length() > MAX_READ_CHARS ? content.substring(0, MAX_READ_CHARS) : content);
            result.put("truncated", content.length() > MAX_READ_CHARS);
            return result;
        });
    }

    public Map<String, Object> writeFile(String path, String content) {
        return guarded(() -> {
            if (content == null) {
                return error("content fehlt.");
            }
            if (content.length() > MAX_WRITE_CHARS) {
                return error("Inhalt zu groß: höchstens " + MAX_WRITE_CHARS + " Zeichen.");
            }
            Path file = workspace().resolve(path);
            if (Files.isDirectory(file)) {
                return error("Pfad ist ein Verzeichnis: " + path);
            }
            Files.createDirectories(file.getParent());
            boolean created = !Files.exists(file);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", workspace().relative(file));
            result.put("created", created);
            result.put("chars", content.length());
            return result;
        });
    }

    private Workspace workspace() {
        if (workspace == null) {
            throw new IllegalStateException(Workspace.ENVIRONMENT_KEY + " ist nicht gesetzt; "
                    + "die Datei-Tools haben kein Arbeitsverzeichnis.");
        }
        return workspace;
    }

    private boolean skipped(Path path) {
        for (Path part : workspace().root().relativize(path)) {
            if (SKIPPED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private interface IoCall {
        Map<String, Object> call() throws IOException;
    }

    private static Map<String, Object> guarded(IoCall call) {
        try {
            return call.call();
        } catch (IllegalArgumentException error) {
            return error(error.getMessage());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }

    private Tool listFilesTool() {
        return FunctionTool.of(
                "list_files",
                "Listet die Dateien im Arbeitsverzeichnis rekursiv auf (ohne .git, target und build).",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "directory", Map.of("type", "string",
                                        "description", "Unterverzeichnis relativ zum Arbeitsverzeichnis; leer für alles.")),
                        "additionalProperties", false),
                arguments -> listFiles(string(arguments, "directory")));
    }

    private Tool readFileTool() {
        return FunctionTool.of(
                "read_file",
                "Liest eine Textdatei aus dem Arbeitsverzeichnis.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Pfad relativ zum Arbeitsverzeichnis.")),
                        "required", List.of("path"),
                        "additionalProperties", false),
                arguments -> readFile(string(arguments, "path")));
    }

    private Tool writeFileTool() {
        return FunctionTool.of(
                "write_file",
                "Schreibt eine Textdatei im Arbeitsverzeichnis vollständig neu oder legt sie an.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Pfad relativ zum Arbeitsverzeichnis."),
                                "content", Map.of("type", "string", "description", "Vollständiger neuer Dateiinhalt.")),
                        "required", List.of("path", "content"),
                        "additionalProperties", false),
                arguments -> writeFile(string(arguments, "path"), string(arguments, "content")),
                ToolCategory.WRITE);
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : value.toString();
    }
}
