package com.zuluindustries.agenten.workflow;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Status eines Workflow-Laufs oder -Schritts. */
public enum RunStatus {
    RUNNING,
    WAITING_FOR_APPROVAL,
    WAITING_FOR_INPUT,
    WAITING_FOR_EVENT,
    WAITING_FOR_SUBWORKFLOW,
    COMPLETED,
    FAILED,
    CANCELLED,
    REJECTED;

    @JsonValue
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
