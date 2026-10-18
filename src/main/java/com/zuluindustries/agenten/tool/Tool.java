package com.zuluindustries.agenten.tool;

import java.util.Map;
import java.util.Set;

/** Ein vom Modell aufrufbares Werkzeug. */
public interface Tool {

    ToolSpec spec();

    /** Sicherheitskategorien des Tools; leer für rein lesende, lokale Tools. */
    default Set<ToolCategory> categories() {
        return Set.of();
    }

    /**
     * Führt das Tool aus. Das Ergebnis muss als JSON serialisierbar sein und
     * sollte nur die tatsächlich benötigten Daten enthalten.
     */
    Object execute(Map<String, Object> arguments);

    default String name() {
        return spec().name();
    }
}
