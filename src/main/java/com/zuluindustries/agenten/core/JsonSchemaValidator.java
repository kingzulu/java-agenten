package com.zuluindustries.agenten.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.SpecVersionDetector;
import com.networknt.schema.ValidationMessage;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Vollständige JSON-Schema-Prüfung (Draft 4 bis 2020-12) über
 * {@code com.networknt:json-schema-validator}. Ohne {@code $schema} gilt Draft 2020-12.
 * Formatangaben wie {@code date} oder {@code email} werden ebenfalls geprüft.
 */
public final class JsonSchemaValidator {

    private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder()
            .locale(Locale.GERMAN)
            .formatAssertionsEnabled(true)
            .build();

    private JsonSchemaValidator() {
    }

    public static void validate(JsonNode value, JsonNode schema) {
        SpecVersion.VersionFlag version = SpecVersionDetector.detectOptionalVersion(schema, false)
                .orElse(SpecVersion.VersionFlag.V202012);
        Set<ValidationMessage> errors = JsonSchemaFactory.getInstance(version)
                .getSchema(schema, CONFIG)
                .validate(value);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Die Ausgabe entspricht nicht dem Schema: "
                    + errors.stream().map(JsonSchemaValidator::describe).sorted().collect(Collectors.joining("; ")));
        }
    }

    /** Fehler am Wurzelobjekt haben einen leeren Pfad; sie werden mit {@code $} gekennzeichnet. */
    private static String describe(ValidationMessage error) {
        String message = error.getMessage();
        return message.startsWith(":") ? "$" + message : message;
    }
}
