package com.zuluindustries.agenten.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Ein Schritt einer Workflow-Definition; entspricht {@code WorkflowStepDefinition} in Python. */
public record WorkflowStep(
        StepKind kind,
        String next,
        String agent,
        String prompt,
        String path,
        ConditionOperator operator,
        Object value,
        String onTrue,
        String onFalse,
        Map<String, String> mapping,
        Map<String, Object> values,
        String action,
        Map<String, Object> parameters,
        Map<String, String> resultMapping,
        String workflow,
        Boolean persistInput,
        Boolean persistOutput) {

    public WorkflowStep {
        if (kind == null) {
            throw new IllegalArgumentException("Ein Schritt benötigt 'kind'.");
        }
        mapping = mapping == null ? Map.of() : new LinkedHashMap<>(mapping);
        values = values == null ? Map.of() : new LinkedHashMap<>(values);
        parameters = parameters == null ? Map.of() : new LinkedHashMap<>(parameters);
        resultMapping = resultMapping == null ? Map.of() : new LinkedHashMap<>(resultMapping);
        persistInput = persistInput == null || persistInput;
        persistOutput = persistOutput == null || persistOutput;
        validate(kind, next, agent, path, operator, onTrue, onFalse, mapping, values, action, resultMapping,
                workflow, persistOutput);
    }

    private static void validate(StepKind kind, String next, String agent, String path, ConditionOperator operator,
                                 String onTrue, String onFalse, Map<String, String> mapping,
                                 Map<String, Object> values, String action, Map<String, String> resultMapping,
                                 String workflow, boolean persistOutput) {
        if (!persistOutput && !Set.of(StepKind.ACTION, StepKind.TRANSFORM, StepKind.AGENT).contains(kind)) {
            throw new IllegalArgumentException(
                    "Flüchtige Ausgaben werden nur für Aktionen, Agenten und Transformationen unterstützt.");
        }
        if (kind == StepKind.AGENT && isBlank(agent)) {
            throw new IllegalArgumentException("Ein Agentenschritt benötigt 'agent'.");
        }
        if (kind == StepKind.CONDITION) {
            if (isBlank(path) || operator == null) {
                throw new IllegalArgumentException("Eine Bedingung benötigt 'path' und 'operator'.");
            }
            if (isBlank(onTrue) || isBlank(onFalse)) {
                throw new IllegalArgumentException("Eine Bedingung benötigt 'on_true' und 'on_false'.");
            }
            if (next != null) {
                throw new IllegalArgumentException("Eine Bedingung verwendet Verzweigungen statt 'next'.");
            }
        }
        if (kind == StepKind.TRANSFORM && mapping.isEmpty() && values.isEmpty()) {
            throw new IllegalArgumentException("Eine Transformation benötigt 'mapping' oder 'values'.");
        }
        if (kind == StepKind.ACTION && isBlank(action)) {
            throw new IllegalArgumentException("Ein Aktionsschritt benötigt 'action'.");
        }
        if (!resultMapping.isEmpty() && kind != StepKind.ACTION) {
            throw new IllegalArgumentException("'result_mapping' ist nur für Aktionen erlaubt.");
        }
        if (kind == StepKind.SUBWORKFLOW && isBlank(workflow)) {
            throw new IllegalArgumentException("Ein Subworkflow-Schritt benötigt 'workflow'.");
        }
        if (kind == StepKind.END && next != null) {
            throw new IllegalArgumentException("Ein Endschritt darf keinen Nachfolger haben.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
