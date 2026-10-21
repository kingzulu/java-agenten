package com.zuluindustries.agenten.service.code;

import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.tool.workspace.Workspace;
import com.zuluindustries.agenten.workflow.WorkflowActionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CodeSchwarmTest {

    @TempDir
    Path root;
    final List<List<String>> commands = new ArrayList<>();

    private CodeSchwarm schwarm(CommandRunner.Result mavenResult) {
        CommandRunner runner = (command, directory, timeout) -> {
            commands.add(command);
            return mavenResult;
        };
        return new CodeSchwarm(Workspace.of(root), runner, List.of("mvn"), Duration.ofSeconds(5));
    }

    @Test
    void reportsGreenTests() {
        Map<String, Object> result = schwarm(new CommandRunner.Result(0, "[INFO] Tests run: 3\n[INFO] BUILD SUCCESS", false))
                .runTests();

        assertEquals(List.of(List.of("mvn", "-B", "test")), commands);
        assertEquals(true, result.get("passed"));
        assertEquals("[INFO] Tests run: 3\n[INFO] BUILD SUCCESS", result.get("summary"));
    }

    @Test
    void reportsRedTestsCompactly() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            output.append("[INFO] Download ").append(i).append('\n');
        }
        output.append("[ERROR] AppTest.addiert:12 expected: <3> but was: <2>\n[INFO] BUILD FAILURE\n");

        Map<String, Object> result = schwarm(new CommandRunner.Result(1, output.toString(), false)).runTests();

        assertEquals(false, result.get("passed"));
        assertEquals("[ERROR] AppTest.addiert:12 expected: <3> but was: <2>\n[INFO] BUILD FAILURE", result.get("summary"));
        assertEquals(CodeSchwarm.TAIL_LINES, result.get("tail").toString().lines().count());
    }

    @Test
    void timeoutCountsAsRed() {
        Map<String, Object> result = schwarm(new CommandRunner.Result(-1, "", true)).runTests();
        assertEquals(false, result.get("passed"));
        assertEquals(true, result.get("timed_out"));
    }

    @Test
    void abortActionFailsWithMessage() {
        WorkflowActionRegistry actions = schwarm(new CommandRunner.Result(0, "", false))
                .registerActions(new WorkflowActionRegistry());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> actions.get("abort_workflow").execute(Map.of(), Map.of("message", "Tests rot")));
        assertEquals("Tests rot", error.getMessage());
    }

    @Test
    void diffContainsChangedAndNewFiles() {
        CommandRunner git = CommandRunner.system();
        assumeTrue(git.run(List.of("git", "--version"), root, Duration.ofSeconds(10)).exitCode() == 0);
        Fixtures.write(root.resolve("App.java"), "class App {}\n");
        Fixtures.write(root.resolve(".gitignore"), "target/\n");
        for (List<String> command : List.of(
                List.of("git", "init", "-q"),
                List.of("git", "add", "."),
                List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "start"))) {
            assertEquals(0, git.run(command, root, Duration.ofSeconds(30)).exitCode(), command.toString());
        }
        Fixtures.write(root.resolve("App.java"), "class App { int x; }\n");
        Fixtures.write(root.resolve("AppTest.java"), "class AppTest {}\n");
        Fixtures.write(root.resolve("target/App.class"), "x");

        Map<String, Object> result = new CodeSchwarm(Workspace.of(root), git, List.of("mvn"), Duration.ofSeconds(5))
                .diff();

        String diff = result.get("diff").toString();
        assertTrue(diff.contains("+class App { int x; }"), diff);
        assertTrue(diff.contains("+++ b/AppTest.java\n+class AppTest {}"), diff);
        assertFalse(diff.contains("App.class"), diff);
        assertEquals(List.of("AppTest.java"), result.get("new_files"));
    }

    @Test
    void readsEnvironment() {
        CodeSchwarm unconfigured = CodeSchwarm.fromEnvironment(name -> null);
        assertThrows(IllegalStateException.class, unconfigured::runTests);
        assertThrows(IllegalArgumentException.class, () -> CodeSchwarm.fromEnvironment(
                Map.of(Workspace.ENVIRONMENT_KEY, root.resolve("fehlt").toString())::get));
        assertThrows(IllegalArgumentException.class, () -> CodeSchwarm.fromEnvironment(
                Map.of(CodeSchwarm.TEST_TIMEOUT_KEY, "bald")::get));
    }
}
