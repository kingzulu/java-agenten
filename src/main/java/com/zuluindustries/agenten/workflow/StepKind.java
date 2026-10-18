package com.zuluindustries.agenten.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Unterstützte Schritttypen eines Workflows. */
public enum StepKind {
    /** Führt einen registrierten Agenten aus. */
    AGENT,
    /** Wartet auf eine menschliche Freigabe oder Ablehnung. */
    APPROVAL,
    /** Wartet auf zusätzliche strukturierte Eingaben. */
    INPUT,
    /** Verzweigt anhand eines Werts aus Eingabe oder Ausgabe. */
    CONDITION,
    /** Kopiert und ergänzt strukturierte Workflow-Daten. */
    TRANSFORM,
    /** Führt eine im Code registrierte Aktion aus. */
    ACTION,
    /** Wartet auf ein externes Fortsetzungssignal. */
    WAIT,
    /** Startet einen anderen registrierten Workflow. */
    SUBWORKFLOW,
    /** Schließt den Workflow erfolgreich ab. */
    END;

    @JsonValue
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static StepKind fromKey(String key) {
        for (StepKind kind : values()) {
            if (kind.key().equals(key)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unbekannter Schritttyp '" + key + "'.");
    }
}
