package com.zuluindustries.agenten.model;

/** Wie das Modell Tools verwenden soll. */
public enum ToolStrategy {
    /** Das Modell entscheidet selbst. */
    AUTO,
    /** Das Modell muss genau ein Tool aufrufen (Skill-Routing). */
    REQUIRED,
    /** Keine automatische Toolverwendung. */
    MANUAL;

    public static ToolStrategy fromConfig(String value) {
        if (value == null) {
            return AUTO;
        }
        return switch (value) {
            case "auto" -> AUTO;
            case "required" -> REQUIRED;
            default -> MANUAL;
        };
    }
}
