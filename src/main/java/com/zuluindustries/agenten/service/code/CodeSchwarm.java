package com.zuluindustries.agenten.service.code;

import com.zuluindustries.agenten.service.ServiceManifest;
import com.zuluindustries.agenten.tool.Tool;
import com.zuluindustries.agenten.tool.workspace.Workspace;
import com.zuluindustries.agenten.tool.workspace.WorkspaceTools;
import com.zuluindustries.agenten.workflow.WorkflowActionRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Service „Code-Schwarm“: Agenten entwickeln Java-Code in einem Git-Checkout,
 * {@code mvn test} entscheidet deterministisch über Erfolg (ADR 002).
 *
 * <p>Stellt die Datei-Tools und die Workflow-Aktionen {@code run_maven_tests},
 * {@code workspace_diff} und {@code abort_workflow} bereit. Die Agenten selbst
 * führen keine Befehle aus; Maven und Git ruft nur der Workflow auf.</p>
 */
public final class CodeSchwarm {

    public static final String SERVICE_KEY = "code_schwarm";
    /** Maven-Aufruf, Standard {@code mvn}. */
    public static final String MAVEN_KEY = "SCHWARM_MAVEN";
    /** Zeitlimit eines Testlaufs in Sekunden, Standard 600. */
    public static final String TEST_TIMEOUT_KEY = "SCHWARM_TEST_TIMEOUT_SECONDS";

    static final int SUMMARY_LINES = 60;
    static final int TAIL_LINES = 40;
    static final int MAX_DIFF_CHARS = 60_000;

    /** Maven-Standardhinweise ohne Informationswert für das Modell. */
    private static final List<String> MAVEN_BOILERPLATE = List.of("-> [Help", "[Help 1]", "Re-run Maven",
            "To see the full stack trace", "For more information about the errors", "See dump files");

    private final Workspace workspace;
    private final CommandRunner runner;
    private final List<String> maven;
    private final Duration testTimeout;

    public CodeSchwarm(Workspace workspace, CommandRunner runner, List<String> maven, Duration testTimeout) {
        this.workspace = workspace;
        this.runner = runner;
        this.maven = List.copyOf(maven);
        this.testTimeout = testTimeout;
    }

    /** Liest {@code SCHWARM_WORKSPACE}, {@code SCHWARM_MAVEN} und das Testzeitlimit. */
    public static CodeSchwarm fromEnvironment(Function<String, String> environment) {
        String directory = environment.apply(Workspace.ENVIRONMENT_KEY);
        Workspace workspace = directory == null || directory.isBlank() ? null : Workspace.of(Path.of(directory.strip()));
        String mavenValue = environment.apply(MAVEN_KEY);
        List<String> maven = mavenValue == null || mavenValue.isBlank()
                ? List.of("mvn") : Arrays.asList(mavenValue.strip().split("\\s+"));
        String timeoutValue = environment.apply(TEST_TIMEOUT_KEY);
        long seconds;
        try {
            seconds = timeoutValue == null || timeoutValue.isBlank() ? 600 : Long.parseLong(timeoutValue.strip());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(TEST_TIMEOUT_KEY + " muss eine ganze Zahl sein.", error);
        }
        if (seconds < 1) {
            throw new IllegalArgumentException(TEST_TIMEOUT_KEY + " muss mindestens 1 sein.");
        }
        return new CodeSchwarm(workspace, CommandRunner.system(), maven, Duration.ofSeconds(seconds));
    }

    public static ServiceManifest manifest() {
        return new ServiceManifest(SERVICE_KEY, "Code-Schwarm",
                "Agenten planen, testen, entwickeln und prüfen Java-Code in einem Git-Checkout.",
                List.of("code.workspace.read", "code.workspace.write"),
                List.of("code_planer", "code_testautor", "code_entwickler", "code_reviewer"),
                List.of("code_entwicklung"));
    }

    public List<Tool> tools() {
        return new WorkspaceTools(workspace).tools();
    }

    public WorkflowActionRegistry registerActions(WorkflowActionRegistry actions) {
        return actions
                .register("run_maven_tests", (context, parameters) -> runTests())
                .register("workspace_diff", (context, parameters) -> diff())
                .register("abort_workflow", (context, parameters) -> {
                    Object message = parameters.get("message");
                    throw new IllegalStateException(message == null ? "Workflow abgebrochen." : message.toString());
                });
    }

    /** Führt {@code mvn -B test} aus und liefert das Ergebnis in knapper Form für das Modell. */
    public Map<String, Object> runTests() {
        List<String> command = new ArrayList<>(maven);
        command.add("-B");
        command.add("test");
        CommandRunner.Result result = runner.run(command, workspace().root(), testTimeout);
        List<String> lines = result.output().lines().toList();
        List<String> summary = lines.stream()
                .filter(line -> line.contains("Tests run:") || line.contains("FAIL") || line.contains("ERROR")
                        || line.contains("BUILD"))
                .filter(line -> !line.strip().equals("[ERROR]"))
                .filter(line -> MAVEN_BOILERPLATE.stream().noneMatch(line::contains))
                .limit(SUMMARY_LINES)
                .toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("passed", result.exitCode() == 0 && !result.timedOut());
        output.put("exit_code", result.exitCode());
        output.put("timed_out", result.timedOut());
        output.put("summary", String.join("\n", summary));
        output.put("tail", String.join("\n", lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size())));
        return output;
    }

    /** Änderungen gegenüber dem letzten Commit, einschließlich neuer, nicht ignorierter Dateien. */
    public Map<String, Object> diff() {
        Path root = workspace().root();
        CommandRunner.Result tracked = runner.run(List.of("git", "diff", "--no-color", "HEAD"), root,
                Duration.ofSeconds(60));
        if (tracked.exitCode() != 0) {
            throw new IllegalStateException("git diff ist fehlgeschlagen; das Arbeitsverzeichnis muss ein "
                    + "Git-Repository mit mindestens einem Commit sein.");
        }
        CommandRunner.Result untracked = runner.run(
                List.of("git", "ls-files", "--others", "--exclude-standard"), root, Duration.ofSeconds(60));
        if (untracked.exitCode() != 0) {
            throw new IllegalStateException("git ls-files ist fehlgeschlagen.");
        }
        StringBuilder diff = new StringBuilder(tracked.output());
        List<String> newFiles = untracked.output().lines().filter(line -> !line.isBlank()).toList();
        for (String file : newFiles) {
            diff.append("--- /dev/null\n+++ b/").append(file).append('\n');
            try {
                String content = new String(Files.readAllBytes(workspace().resolve(file)), StandardCharsets.UTF_8);
                content.lines().forEach(line -> diff.append('+').append(line).append('\n'));
            } catch (IOException | IllegalArgumentException error) {
                diff.append("(nicht lesbar)\n");
            }
        }
        boolean truncated = diff.length() > MAX_DIFF_CHARS;
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("diff", truncated ? diff.substring(0, MAX_DIFF_CHARS) : diff.toString());
        output.put("new_files", newFiles);
        output.put("truncated", truncated);
        return output;
    }

    private Workspace workspace() {
        if (workspace == null) {
            throw new IllegalStateException(Workspace.ENVIRONMENT_KEY + " ist nicht gesetzt.");
        }
        return workspace;
    }
}
