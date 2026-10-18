package com.zuluindustries.agenten.workflow;

import com.zuluindustries.agenten.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowDefinitionTest {

    @TempDir
    Path dir;

    private String error(String yaml) {
        Path file = Fixtures.write(dir.resolve("w.yaml"), yaml);
        return assertThrows(IllegalArgumentException.class, () -> WorkflowLoader.load(file)).getMessage();
    }

    @Test
    void loadsDefinitionsKeyedByFileName() {
        Fixtures.write(dir.resolve("einfach.yaml"), """
                name: Einfach
                initial_step: ende
                steps:
                  ende: {kind: end}
                """);

        Map<String, WorkflowDefinition> definitions = WorkflowLoader.loadAll(dir);

        WorkflowDefinition definition = definitions.get("einfach");
        assertEquals("Einfach", definition.name());
        assertEquals(20, definition.limits().maxSteps());
        assertEquals(300, definition.limits().timeoutSeconds());
        assertEquals(StepKind.END, definition.step("ende").kind());
    }

    @Test
    void validatesStepsAndTransitions() {
        assertTrue(error("name: W\ninitial_step: x\nsteps: {a: {kind: end}}\n").contains("initial_step"));
        assertTrue(error("name: W\ninitial_step: a\nsteps: {a: {kind: approval}}\n").contains("benötigt einen Nachfolger"));
        assertTrue(error("name: W\ninitial_step: a\nsteps: {a: {kind: approval, next: b}}\n").contains("unbekannten Schritt 'b'"));
        assertTrue(error("name: W\ninitial_step: a\nsteps: {a: {kind: agent, next: e}, e: {kind: end}}\n").contains("'agent'"));
        assertTrue(error("name: W\ninitial_step: a\nsteps: {a: {kind: condition, path: input.x, operator: equals}}\n")
                .contains("on_true"));
        assertTrue(error("name: W\ninitial_step: a\nsteps: {a: {kind: transform, next: e}, e: {kind: end}}\n")
                .contains("mapping"));
        assertTrue(error("name: W\ninitial_step: a\nsteps: {a: {kind: approval, persist_output: false, next: e}, e: {kind: end}}\n")
                .contains("Flüchtige Ausgaben"));
        assertTrue(error("name: W\ninitial_step: a\nsteps: {a: {kind: end, next: a}}\n").contains("Endschritt"));
        assertTrue(error("name: W\ninitial_step: a\nsteps: {a: {kind: zaubern}}\n").contains("zaubern"));
    }
}
