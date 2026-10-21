package com.zuluindustries.agenten.tool.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Abgeschottetes Arbeitsverzeichnis des Code-Schwarms ({@code SCHWARM_WORKSPACE}).
 *
 * <p>Alle Pfade sind relativ zum Wurzelverzeichnis. Absolute Pfade, {@code ..},
 * das {@code .git}-Verzeichnis und Symlinks, die aus dem Verzeichnis herausführen,
 * werden abgelehnt.</p>
 */
public final class Workspace {

    /** Umgebungsvariable mit dem Arbeitsverzeichnis. */
    public static final String ENVIRONMENT_KEY = "SCHWARM_WORKSPACE";

    private final Path root;

    private Workspace(Path root) {
        this.root = root;
    }

    /** Öffnet ein vorhandenes Verzeichnis als Arbeitsverzeichnis. */
    public static Workspace of(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException(ENVIRONMENT_KEY + " ist kein Verzeichnis: " + directory);
        }
        try {
            return new Workspace(directory.toRealPath());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * Löst einen relativen Pfad auf und prüft, dass er im Arbeitsverzeichnis bleibt.
     *
     * @throws IllegalArgumentException bei einem unzulässigen Pfad
     */
    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("Der Pfad darf nicht leer sein.");
        }
        Path given = Path.of(relative.strip());
        if (given.isAbsolute()) {
            throw new IllegalArgumentException("Nur relative Pfade sind erlaubt: " + relative);
        }
        Path candidate = root.resolve(given).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Pfad liegt außerhalb des Arbeitsverzeichnisses: " + relative);
        }
        if (!candidate.equals(root) && root.relativize(candidate).getName(0).toString().equals(".git")) {
            throw new IllegalArgumentException("Das .git-Verzeichnis ist gesperrt.");
        }
        // Symlinks: der nächste vorhandene Vorfahr muss real im Arbeitsverzeichnis liegen.
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        try {
            if (existing == null || !existing.toRealPath().startsWith(root)) {
                throw new IllegalArgumentException("Pfad führt aus dem Arbeitsverzeichnis heraus: " + relative);
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Pfad kann nicht geprüft werden: " + relative, error);
        }
        return candidate;
    }

    /** Pfad relativ zur Wurzel mit {@code /} als Trenner. */
    public String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
}
