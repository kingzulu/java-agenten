package com.zuluindustries.agenten.core;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.agent.OutputConfig;
import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.support.TestTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPolicyTest {

    @TempDir
    Path root;

    AgentPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new AgentPolicy(new TestTools().registry, root, "Europe/Berlin", Fixtures.FIXED_CLOCK);
    }

    @Test
    void filtersToolsByPermissionsAndDenials() {
        AgentDefinition agent = Fixtures.agent("""
                tools: {allowed: [lookup, save, fetch, remove, publish], denied: [lookup]}
                """);
        assertEquals(List.of(), policy.effectiveAllowedTools(agent));

        AgentDefinition permitted = Fixtures.agent("""
                tools: {allowed: [lookup, save, fetch, remove]}
                permissions: {write_operations: true, external_access: true, delete_operations: true}
                """);
        assertEquals(List.of("lookup", "save", "fetch", "remove"), policy.effectiveAllowedTools(permitted));

        AgentDefinition manual = Fixtures.agent("tools: {allowed: [lookup], strategy: manual}\n");
        assertEquals(List.of(), policy.effectiveAllowedTools(manual));

        AgentDefinition noToolUse = Fixtures.agent("tools: {allowed: [lookup]}\npermissions: {tool_use: false}\n");
        assertEquals(List.of(), policy.effectiveAllowedTools(noToolUse));
    }

    @Test
    void buildsInstructionsInPythonOrder() {
        Fixtures.write(root.resolve("wissen/faq.md"), "Antwort: 42");
        AgentDefinition agent = Fixtures.agent("""
                description: Spezifisch.
                context:
                  system_context: System.
                  knowledge_sources: [wissen/faq.md]
                guardrails:
                  instructions: [Regel 1]
                  forbidden_actions: [Löschen]
                execution: {require_success_check: true}
                """);

        String instructions = policy.buildInstructions(agent, "Frage");

        assertTrue(instructions.startsWith("Du bist ein zuverlässiger KI-Agent."));
        assertTrue(instructions.contains("Agentenspezifische Anweisungen:\nSpezifisch."));
        assertTrue(instructions.contains("- Heute: 2026-10-18\n- Gestern: 2026-10-17\n- Morgen: 2026-10-19"));
        assertTrue(instructions.contains("Systemkontext:\nSystem."));
        assertTrue(instructions.contains("Wissensquellen:\nQuelle: wissen/faq.md\nAntwort: 42"));
        assertTrue(instructions.contains("Zusätzliche Sicherheitsregeln:\n- Regel 1"));
        assertTrue(instructions.contains("Verbotene Handlungen:\n- Löschen"));
        assertTrue(instructions.endsWith("dem verlangten Ausgabeformat entspricht."));
        assertTrue(instructions.indexOf("Aktueller Zeitkontext") < instructions.indexOf("Systemkontext"));

        assertFalse(policy.buildInstructions(agent, null).contains("Aktueller Zeitkontext"));
    }

    @Test
    void rejectsKnowledgeSourcesOutsideProjectAndMissingFiles() {
        AgentDefinition outside = Fixtures.agent("context: {knowledge_sources: [../geheim.txt]}\n");
        AgentDefinition missing = Fixtures.agent("context: {knowledge_sources: [fehlt.md]}\n");

        assertTrue(assertThrows(IllegalArgumentException.class, () -> policy.buildInstructions(outside, "x"))
                .getMessage().contains("außerhalb des Projekts"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> policy.buildInstructions(missing, "x"))
                .getMessage().contains("nicht gefunden"));
    }

    @Test
    void validatesJsonOutputAgainstSchema() {
        Fixtures.write(root.resolve("agents/schemas/person.json"), """
                {"type": "object", "required": ["name"],
                 "properties": {"name": {"type": "string"}, "rolle": {"enum": ["a", "b"]}}}
                """);
        OutputConfig output = new OutputConfig("json", "person");

        assertEquals("{\"name\":\"X\"}", policy.validateOutput("{\"name\":\"X\"}", output));
        assertEquals("Pflichtfeld fehlt: $.name",
                assertThrows(IllegalArgumentException.class, () -> policy.validateOutput("{}", output)).getMessage());
        assertEquals("$.name muss vom Typ 'string' sein.",
                assertThrows(IllegalArgumentException.class,
                        () -> policy.validateOutput("{\"name\": 1}", output)).getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> policy.validateOutput("{\"name\":\"X\",\"rolle\":\"c\"}", output)).getMessage()
                .startsWith("Ungültiger Wert für $.rolle"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> policy.validateOutput("kein json", output)).getMessage().startsWith("Die Ausgabe ist kein valides JSON"));
        assertThrows(IllegalArgumentException.class, () -> policy.validateOutput(" ", OutputConfig.defaults()));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateOutput("{}", new OutputConfig("json", "../person")));
    }

    @Test
    void addsSchemaToInstructionsForJsonOutput() {
        Fixtures.write(root.resolve("agents/schemas/person.json"), "{\"type\": \"object\"}");
        AgentDefinition agent = Fixtures.agent("output: {format: json, schema_name: person}\n");

        String instructions = policy.buildInstructions(agent, "x");

        assertTrue(instructions.contains("Gib ausschließlich valides JSON ohne Markdown-Codeblock aus."));
        assertTrue(instructions.contains("Die Ausgabe muss diesem JSON-Schema entsprechen:"));
    }
}
