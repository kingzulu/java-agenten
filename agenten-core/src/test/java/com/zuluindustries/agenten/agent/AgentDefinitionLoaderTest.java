package com.zuluindustries.agenten.agent;

import com.zuluindustries.agenten.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDefinitionLoaderTest {

    @TempDir
    Path dir;

    @Test
    void appliesDefaultsAndDerivesIdAndNameFromFileName() {
        Path file = Fixtures.write(dir.resolve("mein_agent.yaml"), "description: Prüfe Daten.\n");

        AgentDefinition agent = AgentDefinitionLoader.load(file);

        assertEquals("mein_agent", agent.id());
        assertEquals("mein_agent", agent.name());
        assertEquals("Prüfe Daten.", agent.instructions());
        assertEquals("openai-mini", agent.model().profile());
        assertEquals(0.2, agent.model().temperature());
        assertEquals(4096, agent.model().maxOutputTokens());
        assertEquals("auto", agent.tools().strategy());
        assertEquals("single", agent.execution().mode());
        assertEquals(8, agent.execution().maxIterations());
        assertEquals(120, agent.execution().timeoutSeconds());
        assertEquals(2, agent.execution().maxRetries());
        assertTrue(agent.permissions().toolUse());
        assertFalse(agent.permissions().writeOperations());
        assertEquals(List.of("delete", "external_write"), agent.permissions().requireConfirmationFor());
        assertEquals("session", agent.context().memory().scope());
        assertEquals("text", agent.output().format());
        assertNull(agent.output().schemaName());
    }

    @Test
    void readsSnakeCaseFields() {
        Path file = Fixtures.write(dir.resolve("a.yaml"), """
                id: technisch
                name: Anzeigename
                model:
                  profile: claude-haiku
                  max_output_tokens: 100
                  fallback_profile: openai-mini
                tools:
                  allowed: [list_tasks]
                  denied: [create_task]
                  strategy: manual
                execution:
                  mode: iterative
                  max_tool_steps: 3
                  stop_on_error: true
                permissions:
                  write_operations: true
                  require_confirmation_for: []
                context:
                  system_context: Kontext
                  knowledge_sources: [docs/a.md]
                  memory: {enabled: true, scope: agent}
                output:
                  format: json
                  schema_name: kundenakquise
                unbekannt: wird ignoriert
                """);

        AgentDefinition agent = AgentDefinitionLoader.load(file);

        assertEquals("technisch", agent.id());
        assertEquals("Anzeigename", agent.name());
        assertEquals(100, agent.model().maxOutputTokens());
        assertEquals("openai-mini", agent.model().fallbackProfile());
        assertEquals(List.of("create_task"), agent.tools().denied());
        assertEquals("manual", agent.tools().strategy());
        assertEquals(3, agent.execution().maxToolSteps());
        assertEquals(8, agent.execution().effectiveMaxIterations());
        assertTrue(agent.execution().stopOnError());
        assertEquals(List.of(), agent.permissions().requireConfirmationFor());
        assertEquals("Kontext", agent.context().systemContext());
        assertEquals("agent", agent.context().memory().scope());
        assertTrue(agent.output().isJson());
        assertEquals("kundenakquise", agent.output().schemaName());
    }

    @Test
    void rejectsInvalidValues() {
        Path temperature = Fixtures.write(dir.resolve("t.yaml"), "model: {temperature: 3.5}\n");
        Path strategy = Fixtures.write(dir.resolve("s.yaml"), "tools: {strategy: sometimes}\n");
        Path list = Fixtures.write(dir.resolve("l.yaml"), "- a\n- b\n");

        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentDefinitionLoader.load(temperature))
                .getMessage().contains("temperature"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentDefinitionLoader.load(strategy))
                .getMessage().contains("tools.strategy"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentDefinitionLoader.load(list))
                .getMessage().contains("YAML-Objekt"));
    }

    @Test
    void skipsDisabledAgentsAndRejectsDuplicateIds() {
        Fixtures.write(dir.resolve("a.yaml"), "description: A\n");
        Fixtures.write(dir.resolve("b.yml"), "enabled: false\n");
        Map<String, AgentDefinition> agents = AgentDefinitionLoader.loadAll(dir);
        assertEquals(List.of("a"), List.copyOf(agents.keySet()));

        Fixtures.write(dir.resolve("c.yaml"), "id: a\n");
        assertThrows(IllegalArgumentException.class, () -> AgentDefinitionLoader.loadAll(dir));
    }

    @Test
    void registryReturnsDefaultAssistantWithoutName() {
        AgentRegistry registry = new AgentRegistry(Map.of());

        AgentDefinition agent = registry.get(null, "claude-haiku");

        assertEquals("claude-haiku", agent.model().profile());
        assertEquals(AgentDefinition.DEFAULT_DESCRIPTION, agent.instructions());
        assertThrows(IllegalArgumentException.class, () -> registry.get("fehlt", "openai-mini"));
    }
}
