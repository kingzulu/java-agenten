package com.zuluindustries.agenten.model;

import java.util.List;

/**
 * Providerneutrale Modellantwort.
 *
 * @param id            Antwort-ID des Providers
 * @param text          Ausgabetext (kann leer sein, wenn nur Tools aufgerufen werden)
 * @param complete      ob die Ausgabe vollständig ist
 * @param toolCalls     angeforderte Toolaufrufe
 * @param usage         gemeldeter Tokenverbrauch
 * @param providerState providerinterner Zustand für Folgeaufrufe (z. B. Nachrichtenverlauf)
 */
public record ModelResponse(
        String id,
        String text,
        boolean complete,
        List<ToolCall> toolCalls,
        TokenUsage usage,
        Object providerState) {

    public ModelResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? TokenUsage.ZERO : usage;
    }

    /** Der vollständige Ausgabetext; schlägt bei unvollständigen Antworten fehl. */
    public String outputText() {
        if (!complete) {
            throw new ProviderException(
                    "Die Modellausgabe ist unvollständig; kein vollständiger Text verfügbar.");
        }
        return text == null ? "" : text;
    }
}
