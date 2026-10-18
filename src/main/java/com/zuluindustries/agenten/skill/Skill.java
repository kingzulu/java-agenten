package com.zuluindustries.agenten.skill;

import java.nio.file.Path;
import java.util.List;

/** Ein Skill aus {@code skills/<name>/SKILL.md}: Metadaten, zugeordnete Tools und Arbeitsanweisungen. */
public record Skill(String name, String description, Path path, List<String> tools, String instructions) {

    public Skill {
        tools = tools == null ? List.of() : List.copyOf(tools);
        description = description == null ? "" : description;
        instructions = instructions == null ? "" : instructions;
    }
}
