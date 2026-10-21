package com.zuluindustries.agenten.tool.workspace;

import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.tool.Tool;
import com.zuluindustries.agenten.tool.ToolCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceToolsTest {

    @TempDir
    Path root;
    @TempDir
    Path outside;
    WorkspaceTools tools;

    @BeforeEach
    void setUp() {
        Fixtures.write(root.resolve("pom.xml"), "<project/>");
        Fixtures.write(root.resolve("src/main/java/App.java"), "class App {}");
        Fixtures.write(root.resolve(".git/config"), "[core]");
        Fixtures.write(root.resolve("target/classes/App.class"), "x");
        tools = new WorkspaceTools(Workspace.of(root));
    }

    @Test
    void onlyWriteFileIsWriting() {
        Map<String, Set<ToolCategory>> categories = new java.util.LinkedHashMap<>();
        for (Tool tool : tools.tools()) {
            categories.put(tool.name(), tool.categories());
        }
        assertEquals(Map.of("list_files", Set.of(), "read_file", Set.of(), "write_file", Set.of(ToolCategory.WRITE)),
                categories);
    }

    @Test
    void listsFilesWithoutGitAndBuildOutput() {
        assertEquals(List.of("pom.xml", "src/main/java/App.java"), tools.listFiles(null).get("files"));
        assertEquals(List.of("src/main/java/App.java"), tools.listFiles("src").get("files"));
        assertTrue(tools.listFiles("fehlt").containsKey("error"));
    }

    @Test
    void readsAndWritesInsideWorkspace() throws IOException {
        assertEquals("class App {}", tools.readFile("src/main/java/App.java").get("content"));

        Map<String, Object> written = tools.writeFile("src/test/java/AppTest.java", "class AppTest {}");
        assertEquals(true, written.get("created"));
        assertEquals("class AppTest {}", Files.readString(root.resolve("src/test/java/AppTest.java")));

        assertEquals(false, tools.writeFile("src/test/java/AppTest.java", "class AppTest { }").get("created"));
        assertTrue(tools.readFile("fehlt.txt").containsKey("error"));
    }

    @Test
    void rejectsPathsOutsideWorkspace() throws IOException {
        assertTrue(tools.readFile("../geheim.txt").containsKey("error"));
        assertTrue(tools.readFile(outside.resolve("x").toString()).containsKey("error"));
        assertTrue(tools.writeFile("src/../../x.txt", "x").containsKey("error"));
        assertTrue(tools.writeFile(".git/hooks/pre-commit", "x").containsKey("error"));
        assertTrue(tools.readFile(".git/config").containsKey("error"));

        Files.createSymbolicLink(root.resolve("link"), outside);
        assertTrue(tools.writeFile("link/x.txt", "x").containsKey("error"));
        assertFalse(Files.exists(outside.resolve("x.txt")));
    }

    @Test
    void limitsSizes() {
        assertTrue(tools.writeFile("gross.txt", "x".repeat(WorkspaceTools.MAX_WRITE_CHARS + 1)).containsKey("error"));
        Fixtures.write(root.resolve("lang.txt"), "y".repeat(WorkspaceTools.MAX_READ_CHARS + 10));
        Map<String, Object> read = tools.readFile("lang.txt");
        assertEquals(true, read.get("truncated"));
        assertEquals(WorkspaceTools.MAX_READ_CHARS, read.get("content").toString().length());
    }

    @Test
    void failsWithoutWorkspace() {
        WorkspaceTools unconfigured = new WorkspaceTools(null);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> unconfigured.listFiles(null));
        assertTrue(error.getMessage().contains("SCHWARM_WORKSPACE"));
    }
}
