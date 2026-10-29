package com.zuluindustries.agenten.extension;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.cli.Main;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.model.ProviderRegistry;
import com.zuluindustries.agenten.support.FakeProvider;
import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.util.Environment;
import com.zuluindustries.agenten.workflow.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fachmodule bringen Agenten, Workflows, Aktionen und Befehle mit, ohne dass der Kern sie kennt. */
class PlatformExtensionTest {

    @TempDir
    Path home;

    final FakeProvider openai = new FakeProvider("openai");
    final List<Platform> installed = new ArrayList<>();

    /** Ein Beispielmodul mit allem, was ein Fachmodul beisteuern kann. */
    class Beispiel implements PlatformExtension {

        final Map<String, String> agents;

        Beispiel(Map<String, String> agents) {
            this.agents = agents;
        }

        @Override
        public String name() {
            return "beispiel";
        }

        @Override
        public Map<String, String> agentDefinitions() {
            return agents;
        }

        @Override
        public Map<String, String> workflowDefinitions() {
            return Map.of("beispiel_ablauf", """
                    name: Beispielablauf
                    initial_step: zaehlen
                    steps:
                      zaehlen: {kind: action, action: beispiel_zaehlen, next: ende}
                      ende: {kind: end}
                    """);
        }

        @Override
        public void install(Platform platform) {
            installed.add(platform);
            platform.actions().register("beispiel_zaehlen", (context, parameters) -> Map.of("anzahl", 3));
        }

        @Override
        public List<CliCommand> commands() {
            return List.of(new CliCommand() {
                @Override
                public String option() {
                    return "--beispiel";
                }

                @Override
                public String usage() {
                    return "agenten --beispiel <text>";
                }

                @Override
                public int run(Platform platform, List<String> arguments, PrintStream out, PrintStream err) {
                    out.println("Beispiel: " + String.join(" ", arguments));
                    return 0;
                }
            });
        }
    }

    @BeforeEach
    void copyModels() throws IOException {
        Path repository = Path.of("").toAbsolutePath();
        Fixtures.write(home.resolve("config/models.yaml"), Files.readString(repository.resolve("config/models.yaml")));
    }

    private Platform platform(PlatformExtension... extensions) {
        Environment environment = Environment.of(Map.of("AGENTEN_HOME", home.toString()));
        return Platform.builder(Settings.load(environment), environment)
                .providers(new ProviderRegistry().register(openai))
                .extensions(List.of(extensions))
                .build();
    }

    @Test
    void extensionContributesAgentsWorkflowsActionsAndCommands() {
        Beispiel beispiel = new Beispiel(Map.of("beispiel_agent", "description: Du hilfst.\n",
                "abgeschaltet", "enabled: false\n"));
        Platform platform = platform(beispiel);

        assertEquals(List.of(platform), installed);
        assertSame(beispiel, platform.extension(Beispiel.class).orElseThrow());
        assertTrue(platform.agents().contains("beispiel_agent"));
        assertTrue(!platform.agents().contains("abgeschaltet"));
        WorkflowRun run = platform.workflows().start("beispiel_ablauf", Map.of());
        assertEquals("completed", run.status.key(), run.errorMessage);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = new Main().run(platform, new String[] {"--beispiel", "a", "b"},
                new PrintStream(out, true, StandardCharsets.UTF_8), System.err);
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Beispiel: a b"));

        out.reset();
        new Main().run(platform, new String[0], new PrintStream(out, true, StandardCharsets.UTF_8), System.err);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("agenten --beispiel <text>"));
    }

    @Test
    void duplicateAgentIdsAreRejected() {
        Fixtures.write(home.resolve("agents/definitions/beispiel_agent.yaml"), "description: Lokal.\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> platform(new Beispiel(Map.of("beispiel_agent", "description: Du hilfst.\n"))));
        assertTrue(error.getMessage().contains("beispiel_agent"), error.getMessage());
    }

    @Test
    void coreRunsWithoutExtensions() {
        Platform platform = platform();

        assertTrue(platform.extensions().isEmpty());
        assertTrue(platform.commands().isEmpty());
        // Eine Option eines fehlenden Moduls wird abgelehnt, nicht als Frage an ein Modell geschickt.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(1, new Main().run(platform, new String[] {"--monitor"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err, true, StandardCharsets.UTF_8)));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unbekannte Option '--monitor'"));
        assertTrue(openai.requests.isEmpty());
    }
}
