package com.zuluindustries.agenten.workflow;

import java.time.Instant;

/**
 * Persistierbarer Zustand eines Workflow-Laufs (Tabelle {@code workflow_runs} in Python).
 *
 * <p>Ein- und Ausgaben werden wie in Python als JSON-Text gespeichert.
 * {@link #transientInput} und {@link #transientOutput} gehören nur zum
 * laufenden Aufruf und werden von {@link #copy()} nicht übernommen.</p>
 */
public final class WorkflowRun {

    public String runId;
    public String workflowKey;
    public int workflowVersion;
    public RunStatus status;
    public String currentStepKey;
    public String correlationId;
    public Long customerId;
    public Long userId;
    public String inputJson;
    public String outputJson;
    public int stepCount;
    public long inputTokens;
    public long outputTokens;
    public long totalTokens;
    public long costMicrousd;
    public long costMicroeur;
    public String errorMessage;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant completedAt;

    /** Flüchtige Eingabe bei {@code persist_input = false}. */
    transient String transientInput;
    /** Flüchtige Ausgabe, einmalig in der Startantwort sichtbar. */
    transient String transientOutput;

    public WorkflowRun copy() {
        WorkflowRun copy = new WorkflowRun();
        copy.runId = runId;
        copy.workflowKey = workflowKey;
        copy.workflowVersion = workflowVersion;
        copy.status = status;
        copy.currentStepKey = currentStepKey;
        copy.correlationId = correlationId;
        copy.customerId = customerId;
        copy.userId = userId;
        copy.inputJson = inputJson;
        copy.outputJson = outputJson;
        copy.stepCount = stepCount;
        copy.inputTokens = inputTokens;
        copy.outputTokens = outputTokens;
        copy.totalTokens = totalTokens;
        copy.costMicrousd = costMicrousd;
        copy.costMicroeur = costMicroeur;
        copy.errorMessage = errorMessage;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.completedAt = completedAt;
        return copy;
    }
}
