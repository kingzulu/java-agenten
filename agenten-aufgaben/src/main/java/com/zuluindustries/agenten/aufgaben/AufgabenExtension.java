package com.zuluindustries.agenten.aufgaben;

import com.zuluindustries.agenten.extension.PlatformExtension;
import com.zuluindustries.agenten.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bindet die Aufgabenverwaltung zu Aktenzeichen in die Plattform ein: den Agenten
 * {@code aufgaben}, die Skills {@code aufgaben-anlegen} und {@code aufgaben-auswerten}
 * sowie die Tools {@code create_task}, {@code list_tasks} und {@code complete_task}.
 */
public final class AufgabenExtension implements PlatformExtension {

    public static final String NAME = "aufgaben";
    public static final String AGENT = "aufgaben";
    public static final List<String> SKILLS = List.of("aufgaben-anlegen", "aufgaben-auswerten");

    private final TaskTools tasks = new TaskTools();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, String> agentDefinitions() {
        return Map.of(AGENT, PlatformExtension.resource(getClass(), "agents/" + AGENT + ".yaml"));
    }

    @Override
    public Map<String, String> skillDefinitions() {
        Map<String, String> skills = new LinkedHashMap<>();
        for (String skill : SKILLS) {
            skills.put(skill, PlatformExtension.resource(getClass(), "skills/" + skill + ".md"));
        }
        return skills;
    }

    @Override
    public List<Tool> tools() {
        return tasks.tools();
    }

    /** Die Aufgabenablage dieses Moduls. */
    public TaskTools tasks() {
        return tasks;
    }
}
