package com.zuluindustries.agenten.workflow;

/** Schrittzahl-, Laufzeit- und Kostengrenzen eines Workflows. */
public record WorkflowLimits(Integer maxSteps, Integer timeoutSeconds, Long maxCostMicrousd) {

    public WorkflowLimits {
        maxSteps = maxSteps == null ? 20 : maxSteps;
        timeoutSeconds = timeoutSeconds == null ? 300 : timeoutSeconds;
        if (maxSteps < 1) {
            throw new IllegalArgumentException("limits.max_steps muss mindestens 1 sein.");
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("limits.timeout_seconds muss mindestens 1 sein.");
        }
        if (maxCostMicrousd != null && maxCostMicrousd < 0) {
            throw new IllegalArgumentException("limits.max_cost_microusd darf nicht negativ sein.");
        }
    }

    public static WorkflowLimits defaults() {
        return new WorkflowLimits(null, null, null);
    }
}
