package com.zuluindustries.agenten.service;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Kontrolliertes Manifest eines fachlichen Services (ADR 002);
 * entspricht {@code platform_core/services.py}.
 */
public record ServiceManifest(
        String key,
        String name,
        String description,
        List<String> permissions,
        List<String> agents,
        List<String> pipelines) {

    private static final Pattern SERVICE_KEY = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern REFERENCE = Pattern.compile("^[a-z][a-z0-9_.]*$");

    public ServiceManifest {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        agents = agents == null ? List.of() : List.copyOf(agents);
        pipelines = pipelines == null ? List.of() : List.copyOf(pipelines);
        if (key == null || !SERVICE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Ungültiger Service-Key: " + key);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Service-Name darf nicht leer sein");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Service-Beschreibung darf nicht leer sein");
        }
        check(key, "permissions", permissions);
        check(key, "agents", agents);
        check(key, "pipelines", pipelines);
    }

    private static void check(String key, String field, List<String> values) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("Doppelte Einträge in " + field + ": " + key);
        }
        List<String> invalid = values.stream().filter(value -> !REFERENCE.matcher(value).matches()).toList();
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("Ungültige Einträge in " + field + " für " + key + ": " + invalid);
        }
    }
}
