package com.zuluindustries.agenten.aufgaben;

import com.zuluindustries.agenten.tool.FunctionTool;
import com.zuluindustries.agenten.tool.Tool;
import com.zuluindustries.agenten.tool.ToolCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tools zur Aufgabenverwaltung: {@code create_task}, {@code list_tasks} und
 * {@code complete_task} (Portierung von {@code tools/tasks.py}).
 *
 * <p>Die Python-Version speichert in SQLite; diese Referenzimplementierung hält
 * die Aufgaben im Speicher, bis ein Persistenzadapter angebunden wird.</p>
 */
public final class TaskTools {

    private final Map<Long, Map<String, Object>> tasks = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public List<Tool> tools() {
        return List.of(createTaskTool(), listTasksTool(), completeTaskTool());
    }

    public synchronized Map<String, Object> createTask(String aktenzeichen, String aufgabe,
                                                       String faelligAm, String prioritaet) {
        if (aktenzeichen == null || aktenzeichen.isBlank() || aufgabe == null || aufgabe.isBlank()) {
            throw new IllegalArgumentException("aktenzeichen und aufgabe sind Pflichtfelder.");
        }
        long id = sequence.incrementAndGet();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", id);
        task.put("aktenzeichen", aktenzeichen);
        task.put("aufgabe", aufgabe);
        task.put("faellig_am", faelligAm);
        task.put("prioritaet", prioritaet == null ? "normal" : prioritaet);
        task.put("status", "offen");
        tasks.put(id, task);
        return new LinkedHashMap<>(task);
    }

    public synchronized List<Map<String, Object>> listTasks(String aktenzeichen, String status) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> task : tasks.values()) {
            if (aktenzeichen != null && !aktenzeichen.isBlank() && !aktenzeichen.equals(task.get("aktenzeichen"))) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equals(task.get("status"))) {
                continue;
            }
            result.add(new LinkedHashMap<>(task));
        }
        return result;
    }

    public synchronized Map<String, Object> completeTask(long taskId) {
        Map<String, Object> task = tasks.get(taskId);
        if (task == null) {
            return Map.of("success", false, "message", "Aufgabe " + taskId + " wurde nicht gefunden.");
        }
        task.put("status", "erledigt");
        return Map.of("success", true, "task", new LinkedHashMap<>(task));
    }

    private Tool createTaskTool() {
        return FunctionTool.of(
                "create_task",
                "Legt eine neue Aufgabe zu einem Aktenzeichen an.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "aktenzeichen", Map.of("type", "string", "description", "Aktenzeichen des Vorgangs."),
                                "aufgabe", Map.of("type", "string", "description", "Beschreibung der Aufgabe."),
                                "faellig_am", Map.of("type", "string", "description", "Fälligkeitsdatum im Format JJJJ-MM-TT."),
                                "prioritaet", Map.of("type", "string", "enum", List.of("niedrig", "normal", "hoch"))),
                        "required", List.of("aktenzeichen", "aufgabe"),
                        "additionalProperties", false),
                arguments -> createTask(
                        string(arguments, "aktenzeichen"),
                        string(arguments, "aufgabe"),
                        string(arguments, "faellig_am"),
                        string(arguments, "prioritaet")),
                ToolCategory.WRITE);
    }

    private Tool listTasksTool() {
        return FunctionTool.of(
                "list_tasks",
                "Listet Aufgaben auf, optional gefiltert nach Aktenzeichen oder Status.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "aktenzeichen", Map.of("type", "string"),
                                "status", Map.of("type", "string", "enum", List.of("offen", "erledigt"))),
                        "additionalProperties", false),
                arguments -> listTasks(string(arguments, "aktenzeichen"), string(arguments, "status")));
    }

    private Tool completeTaskTool() {
        return FunctionTool.of(
                "complete_task",
                "Markiert eine Aufgabe als erledigt.",
                Map.of(
                        "type", "object",
                        "properties", Map.of("task_id", Map.of("type", "integer")),
                        "required", List.of("task_id"),
                        "additionalProperties", false),
                arguments -> completeTask(((Number) Objects.requireNonNull(
                        arguments.get("task_id"), "task_id fehlt.")).longValue()),
                ToolCategory.WRITE);
    }

    private static String string(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : value.toString();
    }
}
