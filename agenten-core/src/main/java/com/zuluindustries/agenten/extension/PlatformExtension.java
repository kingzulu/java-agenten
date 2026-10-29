package com.zuluindustries.agenten.extension;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.tool.Tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Ein Fachmodul, das sich in die Plattform einklinkt, ohne dass der Kern es kennt.
 *
 * <p>Module werden über {@link java.util.ServiceLoader} gefunden (Eintrag in
 * {@code META-INF/services/com.zuluindustries.agenten.extension.PlatformExtension}) oder
 * im {@link Platform.Builder} ausdrücklich übergeben. Ein Modul liefert eigene Agenten
 * und Workflows als YAML, eigene Tools und Skills, registriert nach dem Aufbau seine
 * Workflow-Aktionen und kann Befehle für die Kommandozeile beisteuern.</p>
 */
public interface PlatformExtension {

    /** Kurzer, eindeutiger Name, z. B. {@code monitor}. */
    String name();

    /** Agentendefinitionen als YAML-Text je Agent-ID; vorhandene IDs dürfen nicht doppelt vorkommen. */
    default Map<String, String> agentDefinitions() {
        return Map.of();
    }

    /** Workflow-Definitionen als YAML-Text je Workflow-Schlüssel. */
    default Map<String, String> workflowDefinitions() {
        return Map.of();
    }

    /** Tools des Moduls; Namen dürfen nicht doppelt vorkommen. */
    default List<Tool> tools() {
        return List.of();
    }

    /** Skills als Inhalt einer {@code SKILL.md} (mit Frontmatter) je Skill-Name. */
    default Map<String, String> skillDefinitions() {
        return Map.of();
    }

    /** Wird einmal nach dem Aufbau der Plattform aufgerufen, etwa um Workflow-Aktionen zu registrieren. */
    default void install(Platform platform) {
    }

    /** Zusätzliche Befehle der Kommandozeile. */
    default List<CliCommand> commands() {
        return List.of();
    }

    /** Liest eine UTF-8-Ressource relativ zur Klasse {@code owner}. */
    static String resource(Class<?> owner, String name) {
        try (InputStream stream = owner.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("Ressource fehlt: " + name + " (" + owner.getName() + ")");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
