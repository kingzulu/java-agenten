package com.zuluindustries.agenten.monitor;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Ergebnis einer Verfügbarkeitsprüfung. {@link #UNKNOWN} steht für eine
 * fehlgeschlagene Abfrage und wird nie als „nicht verfügbar“ gewertet.
 */
public enum Availability {
    AVAILABLE("verfügbar"),
    UNAVAILABLE("nicht verfügbar"),
    UNKNOWN("unbekannt");

    private final String label;

    Availability(String label) {
        this.label = label;
    }

    /** Anzeigename für Berichte. */
    public String label() {
        return label;
    }

    @JsonValue
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Availability fromKey(String key) {
        for (Availability value : values()) {
            if (value.key().equals(key)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unbekannte Verfügbarkeit '" + key + "'.");
    }
}
