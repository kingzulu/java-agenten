package com.zuluindustries.agenten.workflow;

import java.time.Instant;

/** Persistierbarer Zustand eines ausgeführten Schritts (Tabelle {@code workflow_step_runs}). */
public final class WorkflowStepRun {

    public String runId;
    public String stepKey;
    public int position;
    public StepKind kind;
    public RunStatus status;
    public String agentName;
    public String inputJson;
    public String outputJson;
    public String provider;
    public String model;
    public long inputTokens;
    public long outputTokens;
    public long totalTokens;
    public long costMicrousd;
    public long costMicroeur;
    public String errorMessage;
    public Instant startedAt;
    public Instant completedAt;
    public Instant approvedAt;

    public WorkflowStepRun copy() {
        WorkflowStepRun copy = new WorkflowStepRun();
        copy.runId = runId;
        copy.stepKey = stepKey;
        copy.position = position;
        copy.kind = kind;
        copy.status = status;
        copy.agentName = agentName;
        copy.inputJson = inputJson;
        copy.outputJson = outputJson;
        copy.provider = provider;
        copy.model = model;
        copy.inputTokens = inputTokens;
        copy.outputTokens = outputTokens;
        copy.totalTokens = totalTokens;
        copy.costMicrousd = costMicrousd;
        copy.costMicroeur = costMicroeur;
        copy.errorMessage = errorMessage;
        copy.startedAt = startedAt;
        copy.completedAt = completedAt;
        copy.approvedAt = approvedAt;
        return copy;
    }
}
