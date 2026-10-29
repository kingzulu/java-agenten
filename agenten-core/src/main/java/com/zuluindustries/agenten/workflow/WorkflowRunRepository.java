package com.zuluindustries.agenten.workflow;

import java.util.List;
import java.util.Optional;

/**
 * Persistenz der Workflow-Läufe. Die Engine speichert nach jedem Schritt,
 * sodass wartende Läufe später fortgesetzt werden können.
 */
public interface WorkflowRunRepository {

    void save(WorkflowRun run);

    Optional<WorkflowRun> findByRunId(String runId);

    void saveStep(WorkflowStepRun step);

    /** Alle Schritte eines Laufs, sortiert nach Position. */
    List<WorkflowStepRun> steps(String runId);

    default Optional<WorkflowStepRun> latestStep(String runId) {
        List<WorkflowStepRun> steps = steps(runId);
        return steps.isEmpty() ? Optional.empty() : Optional.of(steps.getLast());
    }
}
