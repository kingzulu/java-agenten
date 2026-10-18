package com.zuluindustries.agenten.support;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Hilfen zum Erzeugen von Definitionen und Dateien in Tests. */
public final class Fixtures {

    public static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC);

    private Fixtures() {
    }

    public static AgentDefinition agent(String yaml) {
        try {
            return Json.DEFINITIONS_YAML.readValue(yaml, AgentDefinition.class).withIdentity("test", "test");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public static Path write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
