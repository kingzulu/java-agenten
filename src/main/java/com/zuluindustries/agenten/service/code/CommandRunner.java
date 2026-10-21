package com.zuluindustries.agenten.service.code;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Führt einen Befehl in einem Verzeichnis aus; austauschbar für Tests. */
@FunctionalInterface
public interface CommandRunner {

    /**
     * @param exitCode Rückgabewert; {@code -1} nach Zeitüberschreitung
     * @param output   Standard- und Fehlerausgabe zusammen
     */
    record Result(int exitCode, String output, boolean timedOut) {
    }

    Result run(List<String> command, Path directory, Duration timeout);

    /** Startet echte Prozesse; die Ausgabe läuft über eine temporäre Datei, damit nichts blockiert. */
    static CommandRunner system() {
        return (command, directory, timeout) -> {
            Path log = null;
            try {
                log = Files.createTempFile("schwarm-", ".log");
                Process process = new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
                process.getOutputStream().close();
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                }
                String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
                return new Result(finished ? process.exitValue() : -1, output, !finished);
            } catch (IOException error) {
                throw new UncheckedIOException("Befehl konnte nicht ausgeführt werden: " + command.getFirst(), error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Befehl wurde unterbrochen: " + command.getFirst(), error);
            } finally {
                if (log != null) {
                    try {
                        Files.deleteIfExists(log);
                    } catch (IOException ignored) {
                        // Temporäre Datei; Aufräumen ist nicht kritisch.
                    }
                }
            }
        };
    }
}
