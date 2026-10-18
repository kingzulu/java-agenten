package com.zuluindustries.agenten.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Bewusst kleine JSON-Schema-Prüfung (type, required, properties, enum),
 * identisch zum Umfang von {@code _validate_json_schema} in Python.
 */
public final class JsonSchemaValidator {

    private JsonSchemaValidator() {
    }

    public static void validate(JsonNode value, JsonNode schema) {
        validate(value, schema, "$");
    }

    private static void validate(JsonNode value, JsonNode schema, String path) {
        String expectedType = schema.path("type").asText(null);
        if (expectedType != null && !matchesType(value, expectedType)) {
            throw new IllegalArgumentException(path + " muss vom Typ '" + expectedType + "' sein.");
        }

        if (value.isObject()) {
            for (JsonNode key : schema.path("required")) {
                if (!value.has(key.asText())) {
                    throw new IllegalArgumentException("Pflichtfeld fehlt: " + path + "." + key.asText());
                }
            }
            Iterator<Map.Entry<String, JsonNode>> properties = schema.path("properties").fields();
            while (properties.hasNext()) {
                Map.Entry<String, JsonNode> property = properties.next();
                if (value.has(property.getKey())) {
                    validate(value.get(property.getKey()), property.getValue(), path + "." + property.getKey());
                }
            }
        }

        JsonNode allowed = schema.get("enum");
        if (allowed != null && allowed.isArray()) {
            boolean found = false;
            for (JsonNode candidate : allowed) {
                if (candidate.equals(value)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("Ungültiger Wert für " + path + ": " + value);
            }
        }
    }

    private static boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }
}
