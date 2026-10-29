package com.zuluindustries.agenten.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registrierte Workflow-Aktionen; entspricht {@code workflows/actions.py}. */
public final class WorkflowActionRegistry {

    private final Map<String, WorkflowAction> actions = new LinkedHashMap<>();

    public WorkflowActionRegistry register(String name, WorkflowAction action) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Ein Aktionsname darf nicht leer sein.");
        }
        if (actions.containsKey(name)) {
            throw new IllegalArgumentException("Workflow-Aktion bereits registriert: " + name);
        }
        actions.put(name, action);
        return this;
    }

    public WorkflowAction get(String name) {
        WorkflowAction action = actions.get(name);
        if (action == null) {
            throw new IllegalArgumentException("Unbekannte Workflow-Aktion '" + name + "'.");
        }
        return action;
    }

    /** Entfernt Registrierungen; hauptsächlich für isolierte Tests. */
    public void clear() {
        actions.clear();
    }
}
