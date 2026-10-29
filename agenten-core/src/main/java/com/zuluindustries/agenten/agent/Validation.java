package com.zuluindustries.agenten.agent;

final class Validation {

    private Validation() {
    }

    static int atLeast(String field, Integer value, int defaultValue, int minimum) {
        int resolved = value == null ? defaultValue : value;
        if (resolved < minimum) {
            throw new IllegalArgumentException(field + " muss mindestens " + minimum + " sein.");
        }
        return resolved;
    }

    static Integer optionalAtLeast(String field, Integer value, int minimum) {
        if (value != null && value < minimum) {
            throw new IllegalArgumentException(field + " muss mindestens " + minimum + " sein.");
        }
        return value;
    }

    static <T> T oneOf(String field, T value, T defaultValue, java.util.Set<T> allowed) {
        T resolved = value == null ? defaultValue : value;
        if (!allowed.contains(resolved)) {
            throw new IllegalArgumentException(
                    "Ungültiger Wert für " + field + ": '" + resolved + "'. Erlaubt: " + allowed);
        }
        return resolved;
    }
}
