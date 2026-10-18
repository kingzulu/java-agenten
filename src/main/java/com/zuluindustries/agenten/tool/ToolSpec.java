package com.zuluindustries.agenten.tool;

import java.util.Map;

/**
 * Providerneutrale Beschreibung eines Tools, wie sie an das Modell gesendet wird
 * (Name, Beschreibung, JSON-Schema der Parameter).
 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {

    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Ein Toolname darf nicht leer sein.");
        }
        description = description == null ? "" : description;
        parameters = parameters == null
                ? Map.of("type", "object", "properties", Map.of())
                : Map.copyOf(parameters);
    }
}
