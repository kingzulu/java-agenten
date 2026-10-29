package com.zuluindustries.agenten.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.zuluindustries.agenten.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Entdeckt Skills in {@code <skillsDir>/*}{@code /SKILL.md}; entspricht {@code skills/loader.py}.
 *
 * <p>Eine SKILL.md beginnt mit YAML-Frontmatter zwischen {@code ---}-Zeilen
 * ({@code name}, {@code description}, {@code tools}). Dateien ohne gültiges
 * Frontmatter oder ohne Namen werden übersprungen.</p>
 */
public final class SkillLoader {

    private SkillLoader() {
    }

    public static List<Skill> discover(Path skillsDir) {
        List<Skill> skills = new ArrayList<>();
        if (!Files.isDirectory(skillsDir)) {
            return skills;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(Files::isDirectory)
                    .map(dir -> dir.resolve("SKILL.md"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(files::add);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        for (Path file : files) {
            Skill skill = parse(file);
            if (skill != null) {
                skills.add(skill);
            }
        }
        return skills;
    }

    static Skill parse(Path file) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        if (!content.startsWith("---")) {
            return null;
        }
        String[] parts = content.split("---", 3);
        if (parts.length < 3) {
            return null;
        }
        JsonNode metadata;
        try {
            metadata = Json.DEFINITIONS_YAML.readTree(parts[1]);
        } catch (IOException error) {
            throw new IllegalArgumentException("Ungültiges Frontmatter in " + file + ": " + error.getMessage(), error);
        }
        if (metadata == null || !metadata.isObject() || !metadata.hasNonNull("name")) {
            return null;
        }
        List<String> tools = new ArrayList<>();
        JsonNode toolsNode = metadata.get("tools");
        if (toolsNode != null && toolsNode.isArray()) {
            toolsNode.forEach(node -> tools.add(node.asText().trim()));
        }
        String description = metadata.hasNonNull("description")
                ? metadata.get("description").asText().trim().replaceAll("\\s+", " ")
                : "";
        return new Skill(metadata.get("name").asText().trim(), description, file, tools, parts[2].strip());
    }
}
