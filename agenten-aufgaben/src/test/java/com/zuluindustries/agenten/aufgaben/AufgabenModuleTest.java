package com.zuluindustries.agenten.aufgaben;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.util.Environment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Das Aufgaben-Modul wird über den ServiceLoader gefunden und bringt Agent, Skills und Tools mit. */
class AufgabenModuleTest {

    private static Platform platform() {
        Environment environment = Environment.of(Map.of("AGENTEN_HOME", Path.of("").toAbsolutePath().toString()));
        return Platform.builder(Settings.load(environment), environment).build();
    }

    @Test
    void shippedModuleIsDiscoveredAndComplete() {
        Platform platform = platform();

        assertTrue(platform.extension(AufgabenExtension.class).isPresent());
        AgentDefinition agent = platform.agents().get(AufgabenExtension.AGENT, null);
        assertEquals(AufgabenExtension.SKILLS, agent.skills());
        AufgabenExtension.SKILLS.forEach(skill -> assertTrue(platform.skills().contains(skill), skill));
        for (String tool : List.of("create_task", "list_tasks", "complete_task")) {
            assertTrue(platform.tools().contains(tool), tool);
        }
        var policy = platform.runner().runtime().policy();
        var prepared = platform.runner().runtime().capabilities()
                .prepare(policy.effectiveAllowedTools(agent), agent.skills());
        assertEquals(2, prepared.skills().size());
        policy.validateResources(agent);
    }

    @Test
    void toolsWorkOnTheModulesTaskStore() {
        Platform platform = platform();
        TaskTools tasks = platform.extension(AufgabenExtension.class).orElseThrow().tasks();

        platform.tools().find("create_task").orElseThrow()
                .execute(Map.of("aktenzeichen", "AZ-1", "aufgabe", "Angebot prüfen"));
        platform.tools().find("complete_task").orElseThrow().execute(Map.of("task_id", 1));

        List<Map<String, Object>> done = tasks.listTasks("AZ-1", "erledigt");
        assertEquals(1, done.size());
        assertEquals("Angebot prüfen", done.getFirst().get("aufgabe"));
    }
}
