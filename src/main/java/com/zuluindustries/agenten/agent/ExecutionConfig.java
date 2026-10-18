package com.zuluindustries.agenten.agent;

import java.util.Set;

/** Ausführungsverhalten: Schritte, Lösungsversuche, Timeout, Retries und Fehlerverhalten. */
public record ExecutionConfig(
        String mode,
        Integer maxIterations,
        Integer maxToolSteps,
        Integer timeoutSeconds,
        Boolean retryOnFailure,
        Integer maxRetries,
        Boolean stopOnError,
        Boolean requireSuccessCheck) {

    public static final String SINGLE = "single";
    public static final String ITERATIVE = "iterative";

    public ExecutionConfig {
        mode = Validation.oneOf("execution.mode", mode, SINGLE, Set.of(SINGLE, ITERATIVE));
        maxIterations = Validation.atLeast("execution.max_iterations", maxIterations, 8, 1);
        maxToolSteps = Validation.optionalAtLeast("execution.max_tool_steps", maxToolSteps, 1);
        timeoutSeconds = Validation.atLeast("execution.timeout_seconds", timeoutSeconds, 120, 1);
        retryOnFailure = retryOnFailure == null || retryOnFailure;
        maxRetries = Validation.atLeast("execution.max_retries", maxRetries, 2, 0);
        stopOnError = stopOnError != null && stopOnError;
        requireSuccessCheck = requireSuccessCheck != null && requireSuccessCheck;
    }

    public static ExecutionConfig defaults() {
        return new ExecutionConfig(null, null, null, null, null, null, null, null);
    }

    /** Anzahl der Lösungsversuche; nur im iterativen Modus größer als eins. */
    public int effectiveMaxIterations() {
        return ITERATIVE.equals(mode) ? maxIterations : 1;
    }

    /** Anzahl der Wiederholungen eines fehlgeschlagenen Provideraufrufs. */
    public int effectiveRetryCount() {
        return retryOnFailure ? maxRetries : 0;
    }
}
