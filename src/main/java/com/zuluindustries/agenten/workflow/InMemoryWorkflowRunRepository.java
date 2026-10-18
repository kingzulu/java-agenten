package com.zuluindustries.agenten.workflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speicherbasierte Ablage. Gespeichert werden Kopien, damit sich das Verhalten
 * wie bei einer Datenbank verhält (flüchtige Daten gehen beim Laden verloren).
 */
public final class InMemoryWorkflowRunRepository implements WorkflowRunRepository {

    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, WorkflowStepRun>> steps = new ConcurrentHashMap<>();

    @Override
    public void save(WorkflowRun run) {
        runs.put(run.runId, run.copy());
    }

    @Override
    public Optional<WorkflowRun> findByRunId(String runId) {
        WorkflowRun run = runs.get(runId);
        return run == null ? Optional.empty() : Optional.of(run.copy());
    }

    @Override
    public void saveStep(WorkflowStepRun step) {
        steps.computeIfAbsent(step.runId, key -> new LinkedHashMap<>()).put(step.position, step.copy());
    }

    @Override
    public List<WorkflowStepRun> steps(String runId) {
        Map<Integer, WorkflowStepRun> stored = steps.getOrDefault(runId, Map.of());
        List<WorkflowStepRun> result = new ArrayList<>();
        stored.values().stream()
                .sorted(Comparator.comparingInt(step -> step.position))
                .forEach(step -> result.add(step.copy()));
        return result;
    }
}
