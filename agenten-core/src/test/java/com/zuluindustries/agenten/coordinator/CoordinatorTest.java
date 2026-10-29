package com.zuluindustries.agenten.coordinator;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.builder.AgentTemplates;
import com.zuluindustries.agenten.core.AgentPermissionException;
import com.zuluindustries.agenten.core.AgentResult;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.model.ModelRequest;
import com.zuluindustries.agenten.model.ProviderRegistry;
import com.zuluindustries.agenten.support.FakeProvider;
import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.util.Environment;
import com.zuluindustries.agenten.util.Json;
import com.zuluindustries.agenten.workflow.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Coordinator ohne externe Modellaufrufe; entspricht {@code tests/test_coordinator.py}. */
class CoordinatorTest {

    static final String TASK = "{\"task\": \"summarize\", \"capabilities\": [\"summarize\"]}";
    static final String PROMPT = "1. Rolle und Ziel\nDu fasst Texte zusammen.";
    static final String GENERATED = """
            id: new_worker
            enabled: false
            description: |
              1. Rolle und Ziel
              Du fasst Texte zusammen.
            capabilities: [summarize]
            """;

    @TempDir
    Path home;

    final FakeProvider openai = new FakeProvider("openai");

    private Platform platform(String... workers) throws IOException {
        Path repository = Path.of("").toAbsolutePath();
        for (String file : new String[] {"config/models.yaml", "agents/definitions/agent_builder.yaml",
                "agents/definitions/agent_creator_agent.yaml", "agents/definitions/prompt_agent.yaml",
                "agents/definitions/coordinator.yaml"}) {
            Fixtures.write(home.resolve(file), Files.readString(repository.resolve(file)));
        }
        for (String worker : workers) {
            Fixtures.write(home.resolve("agents/definitions/" + worker + ".yaml"), """
                    description: Du fasst Texte zusammen.
                    capabilities: [summarize]
                    model: {profile: openai-mini, fallback_profile: claude-haiku}
                    tools: {allowed: [write_agent_definition]}
                    permissions: {write_operations: true}
                    """);
        }
        Environment environment = Environment.of(Map.of("AGENTEN_HOME", home.toString()));
        return Platform.builder(Settings.load(environment), environment)
                .providers(new ProviderRegistry().register(openai))
                .build();
    }

    private static Coordinator coordinator(Platform platform, AtomicLong clock) {
        return new Coordinator(platform.agents(), platform.agentBuilder(), platform.runner().runtime().policy(),
                platform.profiles(), platform.tools(), clock::get);
    }

    private static Coordinator.Result run(Platform platform, Coordinator coordinator, Coordinator.Limits limits) {
        return coordinator.run(TASK, (agent, input) -> platform.runner().run(agent, input, 5), limits);
    }

    @Test
    void delegatesWithoutCapabilitiesAndEvaluatesResult() throws IOException {
        Platform platform = platform("worker");
        openai.then(FakeProvider.text("done")).then(FakeProvider.text("{\"accepted\": true, \"reason\": \"ok\"}"));

        Coordinator.Result result = platform.runCoordinator(TASK);

        assertEquals(Coordinator.Status.COMPLETED, result.status(), result.text());
        assertEquals("worker", result.agent());
        assertEquals("done", result.text());
        assertEquals(Boolean.TRUE, result.evaluation().get("accepted"));
        assertEquals(30, result.totalTokens());
        assertEquals(2, result.modelCalls().size());
        for (ModelRequest request : openai.requests) {
            assertTrue(request.tools().isEmpty());
            assertNull(request.modelConfig().fallbackProfile());
        }
        // Die registrierte Definition bleibt unverändert.
        assertEquals(List.of("write_agent_definition"),
                platform.agents().get("worker", null).tools().allowed());
        // Der Prüfaufruf behandelt Aufgabe und Ergebnis als Daten.
        assertTrue(openai.requests.get(1).input().toString().contains("result"));
    }

    @Test
    void runAgentReturnsCombinedUsage() throws IOException {
        Platform platform = platform("worker");
        openai.then(FakeProvider.text("done")).then(FakeProvider.text("{\"accepted\": true}"));

        AgentResult result = platform.runAgent(platform.agents().get(Coordinator.AGENT_ID, null), TASK, null);

        assertNull(result.error());
        assertEquals("done", result.text());
        assertEquals(30, result.totalTokens());
        assertEquals(2, result.modelCalls().size());
    }

    @Test
    void rejectedResultTriesNextCandidate() throws IOException {
        Platform platform = platform("a", "b");
        openai.then(FakeProvider.text("first")).then(FakeProvider.text("{\"accepted\": false}"))
                .then(FakeProvider.text("second")).then(FakeProvider.text("{\"accepted\": true}"));

        Coordinator.Result result = platform.runCoordinator(TASK);

        assertEquals(Coordinator.Status.COMPLETED, result.status());
        assertEquals("b", result.agent());
        assertEquals(60, result.totalTokens());
    }

    @Test
    void builderDraftNeverWritesOrRegisters() throws IOException {
        Platform platform = platform();
        openai.then(FakeProvider.text(PROMPT)).then(FakeProvider.text(GENERATED));

        Coordinator.Result result = platform.runCoordinator(TASK);

        assertEquals(Coordinator.Status.WAITING_FOR_APPROVAL, result.status(), result.text());
        assertEquals(2, openai.requests.size());
        assertTrue(result.text().startsWith("Agentenentwurf benötigt Freigabe:\n"));
        assertFalse(platform.agents().contains("new_worker"));
        assertFalse(Files.exists(home.resolve("agents/definitions/new_worker.yaml")));
        assertTrue(openai.requests.get(0).input().toString().contains("Erforderliche capabilities:"));

        AgentDefinition proposal = AgentTemplates.validate(result.proposalYaml(), PROMPT).definition();
        assertEquals(Coordinator.proposalDigest(proposal), result.proposalDigest());
        AgentDefinition registered = platform.coordinator().registerProposal(proposal, result.proposalDigest());
        assertTrue(registered.enabled());
        assertTrue(platform.agents().contains("new_worker"));
    }

    @Test
    void draftWithoutRequiredCapabilitiesFails() throws IOException {
        Platform platform = platform();
        openai.then(FakeProvider.text(PROMPT)).then(FakeProvider.text(GENERATED.replace("[summarize]", "[]")));

        Coordinator.Result result = platform.runCoordinator(TASK);

        assertEquals(Coordinator.Status.FAILED, result.status());
        assertTrue(result.error().contains("Fähigkeiten"), result.error());
    }

    @Test
    void invalidGeneratedYamlIsNotRegistered() throws IOException {
        Platform platform = platform();
        openai.then(FakeProvider.text(PROMPT)).then(FakeProvider.text("[invalid, yaml]"));

        Coordinator.Result result = platform.runCoordinator(TASK);

        assertEquals(Coordinator.Status.FAILED, result.status());
        assertEquals(30, result.totalTokens());
        assertFalse(platform.agents().contains("new_worker"));
    }

    @Test
    void registrationNeedsApprovalOfExactDefinition() throws IOException {
        Platform platform = platform();
        AgentDefinition definition = Json.DEFINITIONS_YAML.readValue("id: new_worker\nenabled: false\n",
                AgentDefinition.class);
        Coordinator coordinator = platform.coordinator();

        assertThrows(AgentPermissionException.class, () -> coordinator.registerProposal(definition, null));
        String digest = Coordinator.proposalDigest(definition);
        AgentDefinition changed = new AgentDefinition(definition.id(), definition.name(), definition.version(),
                definition.enabled(), "changed", definition.model(), definition.capabilities(), definition.skills(),
                definition.tools(), definition.execution(), definition.permissions(), definition.guardrails(),
                definition.context(), definition.output());
        assertThrows(AgentPermissionException.class, () -> coordinator.registerProposal(changed, digest));

        assertTrue(coordinator.registerProposal(definition, digest).enabled());
        assertTrue(platform.agents().get("new_worker", null).enabled());
        assertThrows(IllegalArgumentException.class, () -> coordinator.registerProposal(definition, digest));
    }

    @Test
    void unknownToolsReservedIdsAndSkillsCannotBeRegistered() throws IOException {
        Platform platform = platform();
        Coordinator coordinator = platform.coordinator();
        AgentDefinition tools = Json.DEFINITIONS_YAML.readValue(
                "id: new_worker\nenabled: false\ntools: {allowed: [execute_unreviewed_code]}\n", AgentDefinition.class);
        AgentDefinition reserved = Json.DEFINITIONS_YAML.readValue("id: prompt_agent\nenabled: false\n",
                AgentDefinition.class);
        AgentDefinition skills = Json.DEFINITIONS_YAML.readValue("id: new_worker\nenabled: false\nskills: [x]\n",
                AgentDefinition.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> coordinator.registerProposal(tools, Coordinator.proposalDigest(tools)));
        assertTrue(error.getMessage().contains("Neue Tools"));
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.registerProposal(reserved, Coordinator.proposalDigest(reserved)));
        assertThrows(AgentPermissionException.class,
                () -> coordinator.registerProposal(skills, Coordinator.proposalDigest(skills)));
    }

    @Test
    void stopsAtCallLimit() throws IOException {
        Platform platform = platform("worker");
        openai.then(FakeProvider.text("done"));

        Coordinator.Result result = run(platform, coordinator(platform, new AtomicLong()),
                new Coordinator.Limits(1, 32000, Duration.ofSeconds(120)));

        assertEquals(Coordinator.Status.FAILED, result.status());
        assertTrue(result.error().contains("Aufrufzahl"), result.error());
        assertEquals(15, result.totalTokens());
    }

    @Test
    void stopsAtTokenBudget() throws IOException {
        Platform platform = platform("worker");
        openai.then(FakeProvider.text("done"));

        Coordinator.Result result = run(platform, coordinator(platform, new AtomicLong()),
                new Coordinator.Limits(6, 2, Duration.ofSeconds(120)));

        assertEquals(Coordinator.Status.FAILED, result.status());
        assertTrue(result.error().contains("Tokenbudget"), result.error());
        assertEquals(15, result.totalTokens());
        // Die Ausgabetokens wurden auf das Restbudget begrenzt.
        assertEquals(2, openai.requests.getFirst().modelConfig().maxOutputTokens());
    }

    @Test
    void timeoutStillAccountsLateResponse() throws IOException {
        Platform platform = platform("worker");
        AtomicLong clock = new AtomicLong();
        Coordinator coordinator = coordinator(platform, clock);
        openai.then(FakeProvider.text("done"));

        Coordinator.Result result = coordinator.run(TASK, (agent, input) -> {
            clock.set(Duration.ofSeconds(10).toNanos());
            return platform.runner().run(agent, input, 5);
        }, new Coordinator.Limits(6, 32000, Duration.ofSeconds(1)));

        assertEquals(Coordinator.Status.FAILED, result.status());
        assertTrue(result.error().contains("Zeitlimit"), result.error());
        assertEquals(15, result.totalTokens());
    }

    @Test
    void blocksRecursion() throws IOException {
        Platform platform = platform("worker");
        Coordinator coordinator = platform.coordinator();

        Coordinator.Result result = coordinator.run(TASK,
                (agent, input) -> coordinator.run("{}", (a, i) -> null, Coordinator.Limits.defaults()).asAgentResult(),
                Coordinator.Limits.defaults());

        assertEquals(Coordinator.Status.FAILED, result.status());
        assertTrue(result.error().contains("Rekursive"), result.error());
    }

    @Test
    void rejectsInvalidRequests() throws IOException {
        Platform platform = platform("worker");

        for (String input : new String[] {"invalid", "{}", "{\"task\": \"x\", \"capabilities\": []}",
                "{\"task\": \" \", \"capabilities\": [\"summarize\"]}", "{\"task\": \"x\", \"capabilities\": [1]}"}) {
            Coordinator.Result result = platform.runCoordinator(input);
            assertEquals(Coordinator.Status.FAILED, result.status(), input);
        }
        assertTrue(openai.requests.isEmpty());
    }

    @Test
    void workflowAgentStepsRejectCoordinator() throws IOException {
        Fixtures.write(home.resolve("workflows/definitions/koordiniert.yaml"), """
                name: Koordiniert
                initial_step: frage
                steps:
                  frage: {kind: agent, agent: coordinator, next: ende}
                  ende: {kind: end}
                """);
        Platform platform = platform("worker");

        WorkflowRun run = platform.workflows().start("koordiniert", Map.of());

        assertEquals("failed", run.status.key());
        assertTrue(run.errorMessage.contains("Coordinator benötigt strukturierte Eingabe"), run.errorMessage);
        assertTrue(openai.requests.isEmpty());
    }
}
