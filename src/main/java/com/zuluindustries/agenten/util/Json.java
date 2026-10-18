package com.zuluindustries.agenten.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Gemeinsame Jackson-Konfiguration.
 *
 * <p>Definitionen (Agenten, Workflows) verwenden wie im Python-Projekt
 * snake_case-Feldnamen. Unbekannte Felder werden – analog zu Pydantic –
 * ignoriert.</p>
 */
public final class Json {

    public static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static final ObjectMapper DEFINITIONS_YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .build())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private Json() {
    }

    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(error);
        }
    }

    public static String writePretty(Object value) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(error);
        }
    }

    public static JsonNode readTree(String text) {
        try {
            return JSON.readTree(text);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(error);
        }
    }

    public static Map<String, Object> readMap(String text) {
        try {
            return JSON.readValue(text, MAP);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Wandelt einen beliebigen Wert in eine generische Java-Struktur (Map, List, Skalar) um. */
    public static Object toPlain(Object value) {
        return JSON.convertValue(value, Object.class);
    }
}
