package com.zuluindustries.agenten.support;

import com.zuluindustries.agenten.skill.Skill;
import com.zuluindustries.agenten.skill.SkillRegistry;
import com.zuluindustries.agenten.tool.FunctionTool;
import com.zuluindustries.agenten.tool.ToolCategory;
import com.zuluindustries.agenten.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Test-Tools je Sicherheitskategorie mit Aufrufprotokoll. */
public final class TestTools {

    public final List<String> calls = new ArrayList<>();
    public final ToolRegistry registry = new ToolRegistry();

    public TestTools() {
        registry.register(tool("lookup"));
        registry.register(tool("save", ToolCategory.WRITE));
        registry.register(tool("fetch", ToolCategory.EXTERNAL));
        registry.register(tool("remove", ToolCategory.DELETE));
        registry.register(tool("publish", ToolCategory.EXTERNAL, ToolCategory.WRITE));
    }

    private FunctionTool tool(String name, ToolCategory... categories) {
        return FunctionTool.of(name, "Test-Tool " + name, Map.of("type", "object", "properties", Map.of()),
                arguments -> {
                    calls.add(name);
                    return Map.of("tool", name, "arguments", arguments);
                }, categories);
    }

    public static SkillRegistry skills() {
        return new SkillRegistry(List.of(
                new Skill("suche", "Sucht Daten.", null, List.of("lookup"), "Skill Suche: nutze lookup."),
                new Skill("ablage", "Speichert Daten.", null, List.of("save"), "Skill Ablage: nutze save."),
                new Skill("verboten", "Nutzt fetch.", null, List.of("fetch"), "Skill verboten.")));
    }
}
