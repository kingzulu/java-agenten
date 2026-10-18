package com.zuluindustries.agenten.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Umgebungsvariablen mit optionaler {@code .env}-Datei (wie {@code python-dotenv}):
 * bereits gesetzte Variablen haben Vorrang vor Werten aus der Datei.
 */
public final class Environment implements Function<String, String> {

    private final Map<String, String> values;

    private Environment(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static Environment of(Map<String, String> values) {
        return new Environment(values);
    }

    public static Environment system(Path dotEnv) {
        Map<String, String> values = new HashMap<>(readDotEnv(dotEnv));
        values.putAll(System.getenv());
        return new Environment(values);
    }

    @Override
    public String apply(String name) {
        return values.get(name);
    }

    static Map<String, String> readDotEnv(Path file) {
        Map<String, String> values = new HashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return values;
        }
        try {
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = rawLine.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).strip();
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).strip();
                String value = line.substring(separator + 1).strip();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                } else {
                    int comment = value.indexOf(" #");
                    if (comment >= 0) {
                        value = value.substring(0, comment).strip();
                    }
                }
                values.put(key, value);
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return values;
    }
}
