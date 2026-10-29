package com.zuluindustries.agenten.agent;

import java.util.Set;

/** Text- oder JSON-Ausgabe sowie optionale JSON-Schemavalidierung. */
public record OutputConfig(String format, String schemaName) {

    public static final String TEXT = "text";
    public static final String JSON = "json";

    public OutputConfig {
        format = Validation.oneOf("output.format", format, TEXT, Set.of(TEXT, JSON));
    }

    public static OutputConfig defaults() {
        return new OutputConfig(null, null);
    }

    public boolean isJson() {
        return JSON.equals(format);
    }
}
