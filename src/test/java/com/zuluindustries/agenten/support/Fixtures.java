package com.zuluindustries.agenten.support;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.model.ModelPricing;
import com.zuluindustries.agenten.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/** Hilfen zum Erzeugen von Definitionen und Dateien in Tests. */
public final class Fixtures {

    public static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC);

    private Fixtures() {
    }

    /** Feste Testpreise, unabhängig von {@code config/models.yaml}. */
    public static ModelPricing pricing() {
        return new ModelPricing(Map.of(
                "gpt-5.4-mini", ModelPricing.ModelPrice.of("USD", new ModelPricing.Rates(0.75, 0.075, null, 4.50)),
                "claude-haiku-4-5", ModelPricing.ModelPrice.of("USD", new ModelPricing.Rates(1.00, 0.10, 1.25, 5.00)),
                "mistral-small-3.2-24b-instruct-2506",
                ModelPricing.ModelPrice.of("EUR", new ModelPricing.Rates(0.15, 0.35))));
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
