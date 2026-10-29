package com.zuluindustries.agenten.skill;

import com.zuluindustries.agenten.core.AgentPermissionException;
import com.zuluindustries.agenten.tool.ToolSpec;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bekannte Skills; vereint {@code skills/registry.py}, {@code skills/runtime.py} und {@code skills/tool.py}. */
public final class SkillRegistry {

    public static final String ACTIVATE_SKILL_TOOL = "activate_skill";

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillRegistry(Collection<Skill> skills) {
        for (Skill skill : skills) {
            this.skills.put(skill.name(), skill);
        }
    }

    public static SkillRegistry fromDirectory(Path skillsDir) {
        return new SkillRegistry(SkillLoader.discover(skillsDir));
    }

    public static SkillRegistry empty() {
        return new SkillRegistry(List.of());
    }

    public Optional<Skill> find(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public boolean contains(String name) {
        return skills.containsKey(name);
    }

    public Collection<Skill> all() {
        return skills.values();
    }

    /** Erlaubte und bekannte Skills in der Reihenfolge der Freigabe. */
    public List<Skill> available(List<String> allowedSkills) {
        return allowedSkills.stream().filter(skills::containsKey).map(skills::get).toList();
    }

    /** Lädt die Arbeitsanweisungen eines für den Agenten freigegebenen Skills. */
    public String activateAllowed(String name, List<String> allowedSkills) {
        if (!allowedSkills.contains(name)) {
            throw new AgentPermissionException("Skill '" + name + "' ist für diesen Agenten nicht erlaubt.");
        }
        Skill skill = skills.get(name);
        if (skill == null) {
            throw new IllegalArgumentException("Unbekannter Skill '" + name + "'.");
        }
        return skill.instructions();
    }

    /** Das Routing-Tool, mit dem das Modell genau einen der verfügbaren Skills wählt. */
    public static ToolSpec activateSkillTool(List<Skill> availableSkills) {
        List<String> names = availableSkills.stream().map(Skill::name).toList();
        StringBuilder description = new StringBuilder(
                "Wählt genau einen verfügbaren Skill aus und lädt dessen "
                        + "Arbeitsanweisungen und Werkzeuge. Verwende dieses Werkzeug, "
                        + "bevor du die Aufgabe bearbeitest.\n\nVerfügbare Skills:");
        for (Skill skill : availableSkills) {
            description.append('\n').append(skill.name()).append(": ").append(skill.description());
        }
        return new ToolSpec(ACTIVATE_SKILL_TOOL, description.toString(), Map.of(
                "type", "object",
                "properties", Map.of("name", Map.of(
                        "type", "string",
                        "enum", names,
                        "description", "Name des zu aktivierenden Skills.")),
                "required", List.of("name"),
                "additionalProperties", false));
    }
}
