package com.zuluindustries.agenten.core;

import com.zuluindustries.agenten.skill.Skill;
import com.zuluindustries.agenten.skill.SkillRegistry;
import com.zuluindustries.agenten.tool.Tool;
import com.zuluindustries.agenten.tool.ToolRegistry;
import com.zuluindustries.agenten.tool.ToolSpec;

import java.util.List;

/**
 * Bereitet die an das Modell gesendeten Tools vor; entspricht {@code core/capabilities.py}.
 *
 * <p>Ohne Skills erhält das Modell alle freigegebenen Tools. Mit genau einem
 * Skill nur dessen Tools. Mit mehreren Skills zunächst nur das Routing-Tool
 * {@code activate_skill}; erst nach der Auswahl die Tools des gewählten Skills.</p>
 */
public final class Capabilities {

    /** Vorbereitete Tools und die freigegebenen Skills eines Agenten. */
    public record Prepared(List<ToolSpec> tools, List<Skill> skills) {
    }

    private final ToolRegistry tools;
    private final SkillRegistry skills;

    public Capabilities(ToolRegistry tools, SkillRegistry skills) {
        this.tools = tools;
        this.skills = skills;
    }

    /** Tools eines Skills; alle müssen für den Agenten freigegeben und registriert sein. */
    public List<ToolSpec> skillTools(Skill skill, List<String> allowedTools) {
        List<String> disallowed = skill.tools().stream().filter(name -> !allowedTools.contains(name)).toList();
        if (!disallowed.isEmpty()) {
            throw new AgentPermissionException("Skill '" + skill.name() + "' verwendet nicht erlaubte Tools: "
                    + String.join(", ", disallowed));
        }
        List<String> unknown = skill.tools().stream().filter(name -> !tools.contains(name)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Skill '" + skill.name() + "' verwendet unbekannte Tools: "
                    + String.join(", ", unknown));
        }
        return skill.tools().stream().map(name -> tools.find(name).map(Tool::spec).orElseThrow()).toList();
    }

    public Prepared prepare(List<String> allowedTools, List<String> allowedSkills) {
        List<String> unknownSkills = allowedSkills.stream().filter(name -> !skills.contains(name)).toList();
        if (!unknownSkills.isEmpty()) {
            throw new IllegalArgumentException("Unbekannte Skills: " + String.join(", ", unknownSkills));
        }
        List<String> unknownTools = allowedTools.stream().filter(name -> !tools.contains(name)).toList();
        if (!unknownTools.isEmpty()) {
            throw new IllegalArgumentException("Unbekannte Tools: " + String.join(", ", unknownTools));
        }

        List<Skill> agentSkills = skills.available(allowedSkills);
        if (agentSkills.isEmpty()) {
            return new Prepared(tools.specsFor(allowedTools), agentSkills);
        }
        for (Skill skill : agentSkills) {
            skillTools(skill, allowedTools);
        }
        if (agentSkills.size() == 1) {
            return new Prepared(skillTools(agentSkills.getFirst(), allowedTools), agentSkills);
        }
        return new Prepared(List.of(SkillRegistry.activateSkillTool(agentSkills)), agentSkills);
    }
}
