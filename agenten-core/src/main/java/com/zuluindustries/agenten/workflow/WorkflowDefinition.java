package com.zuluindustries.agenten.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ein deklarativer Workflow aus {@code workflows/definitions/*.yaml}. */
public record WorkflowDefinition(
        String name,
        Integer version,
        Boolean enabled,
        String initialStep,
        WorkflowLimits limits,
        Map<String, WorkflowStep> steps) {

    public WorkflowDefinition {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Ein Workflow benötigt einen Namen.");
        }
        version = version == null ? 1 : version;
        if (version < 1) {
            throw new IllegalArgumentException("version muss mindestens 1 sein.");
        }
        enabled = enabled == null || enabled;
        limits = limits == null ? WorkflowLimits.defaults() : limits;
        steps = steps == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(steps));

        if (initialStep == null || !steps.containsKey(initialStep)) {
            throw new IllegalArgumentException("initial_step verweist auf keinen vorhandenen Schritt.");
        }
        for (Map.Entry<String, WorkflowStep> entry : steps.entrySet()) {
            WorkflowStep step = entry.getValue();
            if (step.kind() != StepKind.END && step.kind() != StepKind.CONDITION && step.next() == null) {
                throw new IllegalArgumentException("Schritt '" + entry.getKey() + "' benötigt einen Nachfolger.");
            }
            List<String> targets = new ArrayList<>();
            targets.add(step.next());
            if (step.kind() == StepKind.CONDITION) {
                targets.add(step.onTrue());
                targets.add(step.onFalse());
            }
            for (String target : targets) {
                if (target != null && !steps.containsKey(target)) {
                    throw new IllegalArgumentException("Schritt '" + entry.getKey()
                            + "' verweist auf unbekannten Schritt '" + target + "'.");
                }
            }
        }
    }

    public WorkflowStep step(String key) {
        WorkflowStep step = steps.get(key);
        if (step == null) {
            throw new IllegalStateException("Unbekannter Workflow-Schritt '" + key + "'.");
        }
        return step;
    }
}
