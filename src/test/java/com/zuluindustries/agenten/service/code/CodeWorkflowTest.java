package com.zuluindustries.agenten.service.code;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.model.ProviderRegistry;
import com.zuluindustries.agenten.support.FakeProvider;
import com.zuluindustries.agenten.tool.workspace.Workspace;
import com.zuluindustries.agenten.util.Environment;
import com.zuluindustries.agenten.workflow.RunStatus;
import com.zuluindustries.agenten.workflow.WorkflowActionRegistry;
import com.zuluindustries.agenten.workflow.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kompletter Lauf des Workflows {@code code_entwicklung} mit geskriptetem Modell und Maven. */
class CodeWorkflowTest {

    @TempDir
    Path workspace;
    final FakeProvider anthropic = new FakeProvider("anthropic");
    final Deque<CommandRunner.Result> mavenResults = new ArrayDeque<>();

    private Platform platform() {
        Environment environment = Environment.of(Map.of(
                "AGENTEN_HOME", Path.of("").toAbsolutePath().toString(),
                Workspace.ENVIRONMENT_KEY, workspace.toString()));
        CommandRunner runner = (command, directory, timeout) -> switch (command.getFirst()) {
            case "mvn" -> mavenResults.poll();
            case "git" -> new CommandRunner.Result(0, command.contains("diff") ? "+class App {}\n" : "", false);
            default -> throw new IllegalArgumentException(command.toString());
        };
        WorkflowActionRegistry actions = new CodeSchwarm(Workspace.of(workspace), runner, List.of("mvn"),
                Duration.ofSeconds(5)).registerActions(new WorkflowActionRegistry());
        return Platform.builder(Settings.load(environment), environment)
                .providers(new ProviderRegistry().register(anthropic))
                .actions(actions)
                .build();
    }

    private static CommandRunner.Result red() {
        return new CommandRunner.Result(1, "[ERROR] AppTest.addiert:7 expected: <3> but was: <2>\n[INFO] BUILD FAILURE",
                false);
    }

    private void scriptPlanTestsAndFirstImplementation() {
        anthropic
                .then(FakeProvider.tool("write_file", Map.of("path", ".schwarm/plan.md", "content", "# Plan")))
                .then(FakeProvider.text("Plan steht."))
                .then(FakeProvider.tool("write_file", Map.of("path", "src/test/java/AppTest.java",
                        "content", "class AppTest {}")))
                .then(FakeProvider.text("Tests geschrieben."))
                .then(FakeProvider.tool("write_file", Map.of("path", "src/main/java/App.java",
                        "content", "class App {}")))
                .then(FakeProvider.text("Umgesetzt."));
    }

    @Test
    void retriesUntilGreenThenWaitsForApproval() throws IOException {
        scriptPlanTestsAndFirstImplementation();
        anthropic
                .then(FakeProvider.text("Fehler behoben."))
                .then(FakeProvider.text("Empfehlung: freigeben."));
        mavenResults.add(red());
        mavenResults.add(new CommandRunner.Result(0, "[INFO] BUILD SUCCESS", false));
        Platform platform = platform();

        WorkflowRun run = platform.workflows().start("code_entwicklung", Map.of("task", "Addiere zwei Zahlen."));

        assertEquals(RunStatus.WAITING_FOR_APPROVAL, run.status, run.errorMessage);
        assertEquals("# Plan", Files.readString(workspace.resolve(".schwarm/plan.md")));
        assertEquals("class App {}", Files.readString(workspace.resolve("src/main/java/App.java")));
        assertTrue(Files.exists(workspace.resolve("src/test/java/AppTest.java")));

        // Zweite Entwicklerrunde sieht die Maven-Ausgabe, der Reviewer den Diff.
        String secondRound = anthropic.requests.get(6).input().getFirst().content();
        assertTrue(secondRound.contains("Runde 2"), secondRound);
        assertTrue(secondRound.contains("AppTest.addiert:7"), secondRound);
        String review = anthropic.requests.get(7).input().getFirst().content();
        assertTrue(review.contains("+class App {}"), review);
        assertEquals("claude-haiku-4-5", anthropic.requests.get(7).model());

        WorkflowRun approved = platform.workflows().continueWorkflow(run.runId, true, null, false);
        assertEquals(RunStatus.COMPLETED, approved.status);
        assertTrue(approved.costMicrousd > 0);
    }

    @Test
    void failsAfterThreeRedRounds() {
        scriptPlanTestsAndFirstImplementation();
        anthropic
                .then(FakeProvider.text("Versuch 2."))
                .then(FakeProvider.text("Versuch 3."));
        mavenResults.add(red());
        mavenResults.add(red());
        mavenResults.add(red());

        WorkflowRun run = platform().workflows().start("code_entwicklung", Map.of("task", "Addiere zwei Zahlen."));

        assertEquals(RunStatus.FAILED, run.status);
        assertTrue(run.errorMessage.contains("nach 3 Entwicklerrunden noch rot"), run.errorMessage);
        assertTrue(mavenResults.isEmpty());
    }
}
