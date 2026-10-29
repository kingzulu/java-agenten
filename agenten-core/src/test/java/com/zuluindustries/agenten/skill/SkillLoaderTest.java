package com.zuluindustries.agenten.skill;

import com.zuluindustries.agenten.core.AgentPermissionException;
import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLoaderTest {

    @TempDir
    Path dir;

    @Test
    void discoversSkillsWithFrontmatter() {
        Fixtures.write(dir.resolve("draws/SKILL.md"), """
                ---
                name: lotto-draws
                description: >
                  Liefert gespeicherte Ziehungen
                  für ein Datum.
                tools:
                  - get_ziehungen
                  - get_historical_draw
                ---

                # Lotto Draws

                Anweisungen.
                """);
        Fixtures.write(dir.resolve("ohne/SKILL.md"), "Kein Frontmatter");

        SkillRegistry registry = SkillRegistry.fromDirectory(dir);

        assertEquals(1, registry.all().size());
        Skill skill = registry.find("lotto-draws").orElseThrow();
        assertEquals("Liefert gespeicherte Ziehungen für ein Datum.", skill.description());
        assertEquals(List.of("get_ziehungen", "get_historical_draw"), skill.tools());
        assertEquals("# Lotto Draws\n\nAnweisungen.", skill.instructions());
    }

    @Test
    void activatesOnlyAllowedSkills() {
        SkillRegistry registry = new SkillRegistry(List.of(new Skill("a", "A", null, List.of(), "Text A")));

        assertEquals("Text A", registry.activateAllowed("a", List.of("a")));
        assertThrows(AgentPermissionException.class, () -> registry.activateAllowed("a", List.of()));
        assertThrows(IllegalArgumentException.class, () -> registry.activateAllowed("b", List.of("b")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsRoutingTool() {
        ToolSpec tool = SkillRegistry.activateSkillTool(List.of(
                new Skill("a", "Erster", null, List.of(), ""),
                new Skill("b", "Zweiter", null, List.of(), "")));

        assertEquals("activate_skill", tool.name());
        assertTrue(tool.description().contains("a: Erster\nb: Zweiter"));
        Map<String, Object> name = (Map<String, Object>) ((Map<String, Object>) tool.parameters().get("properties")).get("name");
        assertEquals(List.of("a", "b"), name.get("enum"));
    }
}
