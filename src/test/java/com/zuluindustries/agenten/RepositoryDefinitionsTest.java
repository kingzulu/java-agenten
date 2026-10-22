package com.zuluindustries.agenten;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.monitor.AvailabilityMonitor;
import com.zuluindustries.agenten.util.Environment;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Alle mitgelieferten Agenten, Skills und Workflows müssen gültig und vollständig verdrahtet sein. */
class RepositoryDefinitionsTest {

    @Test
    void shippedDefinitionsAreValid() {
        Environment environment = Environment.of(Map.of("AGENTEN_HOME", Path.of("").toAbsolutePath().toString()));
        Platform platform = Platform.builder(Settings.load(environment), environment).build();

        assertEquals(7, platform.agents().all().size());
        assertEquals(2, platform.skills().all().size());
        assertTrue(platform.workflows().definitions().containsKey("review_and_approve"));
        assertTrue(platform.workflows().definitions().containsKey(AvailabilityMonitor.WORKFLOW));
        assertNotNull(platform.monitor());

        for (AgentDefinition agent : platform.agents().all().values()) {
            assertTrue(platform.profiles().contains(agent.model().profile()), agent.id());
            var policy = platform.runner().runtime().policy();
            platform.runner().runtime().capabilities().prepare(policy.effectiveAllowedTools(agent), agent.skills());
            policy.validateResources(agent);
        }
        platform.workflows().definitions().values().forEach(definition -> definition.steps().values().stream()
                .filter(step -> step.agent() != null)
                .forEach(step -> assertTrue(platform.agents().contains(step.agent()), step.agent())));
    }
}
