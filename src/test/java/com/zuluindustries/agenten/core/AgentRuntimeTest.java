package com.zuluindustries.agenten.core;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.model.Message;
import com.zuluindustries.agenten.model.ModelResponse;
import com.zuluindustries.agenten.model.ProviderException;
import com.zuluindustries.agenten.model.TokenUsage;
import com.zuluindustries.agenten.tool.ToolSpec;
import com.zuluindustries.agenten.model.ToolStrategy;
import com.zuluindustries.agenten.support.FakeProvider;
import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.support.TestTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeTest {

    @TempDir
    Path root;

    TestTools tools;
    AtomicLong nanos;
    AgentRuntime runtime;
    FakeProvider provider;

    @BeforeEach
    void setUp() {
        tools = new TestTools();
        nanos = new AtomicLong();
        AgentPolicy policy = new AgentPolicy(tools.registry, root, "Europe/Berlin", Fixtures.FIXED_CLOCK);
        runtime = new AgentRuntime(tools.registry, TestTools.skills(), policy, nanos::get);
        provider = new FakeProvider();
    }

    private AgentResult run(AgentDefinition agent, String input) {
        return runtime.runTurn(provider, "modell", agent, input, null, 5);
    }

    @Test
    void returnsTextAndRecordsUsage() {
        provider.then(FakeProvider.text("Hallo"));

        AgentResult result = run(Fixtures.agent("description: Test.\n"), "Hi");

        assertEquals("Hallo", result.text());
        assertNull(result.error());
        assertEquals(15, result.totalTokens());
        assertEquals(List.of(new ModelCall(0, 0, "initial", 10, 5, 15)), result.usage().modelCalls());
        assertEquals("fake", result.usage().provider());
        assertEquals(List.of(Message.user("Hi")), provider.requests.getFirst().input());
        assertTrue(provider.requests.getFirst().instructions().contains("Agentenspezifische Anweisungen:\nTest."));
    }

    @Test
    void executesAllowedToolAndSendsResultInFollowup() {
        provider.then(FakeProvider.tool("lookup", Map.of("q", "x"))).then(FakeProvider.text("Fertig"));

        AgentResult result = run(Fixtures.agent("tools: {allowed: [lookup]}\n"), "Suche");

        assertEquals("Fertig", result.text());
        assertEquals(List.of("lookup"), tools.calls);
        assertEquals(List.of("lookup"), provider.requests.getFirst().tools().stream().map(ToolSpec::name).toList());
        FakeProvider.Followup followup = provider.followups.getFirst();
        assertEquals(Map.of("tool", "lookup", "arguments", Map.of("q", "x")), followup.results().getFirst());
        assertEquals(List.of("initial", "followup"),
                result.usage().modelCalls().stream().map(ModelCall::callType).toList());
        assertEquals(1, result.usage().modelCalls().get(1).step());
        assertEquals(30, result.totalTokens());
    }

    @Test
    void blocksToolsThatAreNotAllowed() {
        provider.then(FakeProvider.tool("save", Map.of()));

        AgentResult result = run(Fixtures.agent("tools: {allowed: [lookup]}\n"), "x");

        assertEquals("Tool 'save' ist für diesen Agenten nicht erlaubt.", result.error());
        assertEquals("Agentenfehler: Tool 'save' ist für diesen Agenten nicht erlaubt.", result.text());
        assertTrue(tools.calls.isEmpty());
        assertEquals(15, result.totalTokens());
    }

    @Test
    void rejectsUnregisteredTools() {
        provider.then(FakeProvider.tool("unbekannt", Map.of()));

        AgentResult result = run(Fixtures.agent("{}"), "x");

        assertEquals("Tool 'unbekannt' ist nicht registriert.", result.error());
    }

    @Test
    void requiresConfirmationForExternalWrites() {
        provider.then(FakeProvider.tool("publish", Map.of()));
        AgentDefinition agent = Fixtures.agent("""
                tools: {allowed: [publish]}
                permissions: {write_operations: true, external_access: true}
                """);

        AgentResult result = run(agent, "x");

        assertTrue(result.error().contains("benötigt eine Bestätigung"));
        assertTrue(tools.calls.isEmpty());
    }

    @Test
    void writeToolRunsWhenPermitted() {
        provider.then(FakeProvider.tool("save", Map.of())).then(FakeProvider.text("ok"));
        AgentDefinition agent = Fixtures.agent("tools: {allowed: [save]}\npermissions: {write_operations: true}\n");

        assertEquals("ok", run(agent, "x").text());
        assertEquals(List.of("save"), tools.calls);
    }

    @Test
    void stopOnErrorRethrows() {
        provider.then(FakeProvider.tool("save", Map.of()));
        AgentDefinition agent = Fixtures.agent("execution: {stop_on_error: true}\n");

        assertThrows(AgentPermissionException.class, () -> run(agent, "x"));
    }

    @Test
    void singleSkillAddsInstructionsAndTools() {
        provider.then(FakeProvider.text("ok"));
        AgentDefinition agent = Fixtures.agent("skills: [suche]\ntools: {allowed: [lookup]}\n");

        run(agent, "x");

        assertTrue(provider.requests.getFirst().instructions().endsWith("Skill Suche: nutze lookup."));
        assertEquals(ToolStrategy.AUTO, provider.requests.getFirst().toolStrategy());
    }

    @Test
    void routesBetweenMultipleSkills() {
        provider.then(FakeProvider.tool("activate_skill", Map.of("name", "ablage")))
                .then(FakeProvider.tool("save", Map.of()))
                .then(FakeProvider.text("gespeichert"));
        AgentDefinition agent = Fixtures.agent("""
                skills: [suche, ablage]
                tools: {allowed: [lookup, save]}
                permissions: {write_operations: true}
                """);

        AgentResult result = run(agent, "Speichere");

        assertEquals("gespeichert", result.text());
        assertEquals(ToolStrategy.REQUIRED, provider.requests.getFirst().toolStrategy());
        assertEquals(List.of("activate_skill"),
                provider.requests.getFirst().tools().stream().map(ToolSpec::name).toList());
        assertEquals("Skill Ablage: nutze save.", provider.followups.getFirst().results().getFirst());
        assertEquals(List.of("save"), provider.followups.getFirst().request().tools().stream().map(ToolSpec::name).toList());
        assertEquals(List.of("save"), tools.calls);
    }

    @Test
    void routingStepMustSelectExactlyOneSkill() {
        provider.then(FakeProvider.text("ohne Auswahl"));
        AgentDefinition agent = Fixtures.agent("skills: [suche, ablage]\ntools: {allowed: [lookup, save]}\n"
                + "permissions: {write_operations: true}\n");

        assertEquals("Der Skill-Routing-Schritt muss genau einen Skill auswählen.", run(agent, "x").error());
    }

    @Test
    void iterativeModeRequestsCorrectionForInvalidJson() {
        provider.then(FakeProvider.text("kein json")).then(FakeProvider.text("{\"ok\": true}"));
        AgentDefinition agent = Fixtures.agent("""
                output: {format: json}
                execution: {mode: iterative, max_iterations: 2}
                """);

        AgentResult result = run(agent, "Gib JSON");

        assertEquals("{\"ok\": true}", result.text());
        String correction = provider.requests.get(1).input().getFirst().content();
        assertTrue(correction.startsWith("Gib JSON\n\nDer vorherige Lösungsversuch war ungültig: Die Ausgabe ist kein valides JSON"));
        assertEquals("iteration", result.usage().modelCalls().get(1).callType());
    }

    @Test
    void singleModeReportsInvalidOutputAsError() {
        provider.then(FakeProvider.text("kein json"));

        AgentResult result = run(Fixtures.agent("output: {format: json}\n"), "x");

        assertTrue(result.failed());
        assertTrue(result.text().startsWith("Agentenfehler: Die Ausgabe ist kein valides JSON"));
    }

    @Test
    void stopsAfterMaximumToolSteps() {
        provider.then(FakeProvider.tool("lookup", Map.of()))
                .then(FakeProvider.tool("lookup", Map.of()));
        AgentDefinition agent = Fixtures.agent("tools: {allowed: [lookup]}\nexecution: {max_tool_steps: 1}\n");

        AgentResult result = run(agent, "x");

        assertEquals("Der Agent wurde beendet, weil die maximale Anzahl der Verarbeitungsschritte erreicht wurde.",
                result.text());
        assertFalse(result.failed());
    }

    @Test
    void retriesFailedProviderCalls() {
        provider.thenFail(new ProviderException("kaputt")).then(FakeProvider.text("ok"));
        AgentDefinition agent = Fixtures.agent("execution: {retry_on_failure: true, max_retries: 1}\n");

        assertEquals("ok", run(agent, "x").text());

        FakeProvider noRetry = new FakeProvider().thenFail(new ProviderException("kaputt"));
        AgentResult failed = runtime.runTurn(noRetry, "m",
                Fixtures.agent("execution: {retry_on_failure: false}\n"), "x", null, 5);
        assertEquals("kaputt", failed.error());
    }

    @Test
    void lateResponseCountsAsTimeoutButKeepsUsage() {
        AgentDefinition agent = Fixtures.agent("execution: {timeout_seconds: 1}\n");
        FakeProvider slow = new FakeProvider() {
            @Override
            public ModelResponse createResponse(com.zuluindustries.agenten.model.ModelRequest request) {
                nanos.addAndGet(2_000_000_000L);
                return new ModelResponse("r", "zu spät", true, List.of(), TokenUsage.of(7, 3), null);
            }
        };

        AgentResult result = runtime.runTurn(slow, "m", agent, "x", null, 5);

        assertEquals("Zeitlimit des Agenten überschritten.", result.error());
        assertEquals(List.of(new ModelCall(0, 0, "late_response", 7, 3, 10)), result.usage().modelCalls());
        assertEquals(10, result.totalTokens());
    }

    @Test
    void conversationKeepsHistory() {
        Conversation conversation = new Conversation(1);
        provider.then(FakeProvider.text("A1")).then(FakeProvider.text("A2")).then(FakeProvider.text("A3"));
        AgentDefinition agent = Fixtures.agent("{}");

        runtime.runTurn(provider, "m", agent, "F1", conversation, 5);
        runtime.runTurn(provider, "m", agent, "F2", conversation, 5);
        runtime.runTurn(provider, "m", agent, "F3", conversation, 5);

        assertEquals(List.of(Message.user("F2"), Message.assistant("A2"), Message.user("F3")),
                provider.requests.get(2).input());
        assertEquals(List.of(Message.user("F3"), Message.assistant("A3")), conversation.messages());
    }

    @Test
    void incompleteOutputIsAnError() {
        provider.then(new ModelResponse("r", "halb", false, List.of(), TokenUsage.ZERO, null));

        assertTrue(run(Fixtures.agent("{}"), "x").error().contains("unvollständig"));
    }
}
