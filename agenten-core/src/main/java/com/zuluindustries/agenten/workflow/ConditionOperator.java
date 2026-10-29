package com.zuluindustries.agenten.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/** Vergleichsoperatoren für Bedingungsschritte. */
public enum ConditionOperator {
    EQUALS,
    NOT_EQUALS,
    EXISTS,
    NOT_EXISTS,
    TRUTHY,
    FALSY,
    CONTAINS,
    GREATER_THAN,
    LESS_THAN;

    @JsonValue
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ConditionOperator fromKey(String key) {
        for (ConditionOperator operator : values()) {
            if (operator.key().equals(key)) {
                return operator;
            }
        }
        throw new IllegalArgumentException("Unbekannter Operator '" + key + "'.");
    }
}
