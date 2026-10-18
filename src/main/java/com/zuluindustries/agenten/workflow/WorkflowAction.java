package com.zuluindustries.agenten.workflow;

import java.util.Map;

/**
 * Eine explizit registrierte Workflow-Aktion. YAML darf keine beliebigen
 * Funktionen ausführen; Anwendungen registrieren erlaubte Aktionen im Code.
 *
 * <p>Liefert die Aktion eine Map mit {@code provider}, {@code model},
 * {@code input_tokens}, {@code output_tokens}, {@code total_tokens},
 * {@code cost_microusd} oder {@code cost_microeur}, wird der Verbrauch am
 * Schritt und Lauf verbucht.</p>
 */
@FunctionalInterface
public interface WorkflowAction {

    Object execute(Map<String, Object> context, Map<String, Object> parameters);
}
