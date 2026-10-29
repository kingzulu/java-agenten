package com.zuluindustries.agenten.monitor;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.extension.CliCommand;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code agenten --monitor}: ein Lauf des Verfügbarkeitsmonitors über den Workflow
 * {@value AvailabilityMonitor#WORKFLOW}. Die Übersicht wird auch dann ausgegeben, wenn der
 * anschließende Bericht scheitert.
 */
final class MonitorCommand implements CliCommand {

    private static final DateTimeFormatter STAND = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final MonitorExtension extension;

    MonitorCommand(MonitorExtension extension) {
        this.extension = extension;
    }

    @Override
    public String option() {
        return "--monitor";
    }

    @Override
    public String usage() {
        return "agenten --monitor";
    }

    @Override
    @SuppressWarnings("unchecked")
    public int run(Platform platform, List<String> arguments, PrintStream out, PrintStream err) {
        if (!arguments.isEmpty()) {
            err.println("Aufruf: " + usage());
            return 1;
        }
        if (extension.monitor() == null) {
            err.println("Fehler: " + MonitorConfig.RELATIVE_PATH + " fehlt; der Verfügbarkeitsmonitor ist nicht konfiguriert.");
            return 1;
        }
        Map<String, Object> data;
        try {
            data = platform.workflows().serialize(platform.workflows().start(AvailabilityMonitor.WORKFLOW, Map.of()));
        } catch (IllegalArgumentException error) {
            err.println("Fehler: " + error.getMessage());
            return 1;
        }
        Map<String, Object> check = null;
        String report = null;
        for (Map<String, Object> step : (List<Map<String, Object>>) data.get("steps")) {
            if (!"completed".equals(step.get("status")) || !(step.get("output") instanceof Map<?, ?> output)) {
                continue;
            }
            if ("pruefen".equals(step.get("step"))) {
                check = (Map<String, Object>) output;
            } else if ("bericht".equals(step.get("step")) && output.get("text") != null) {
                report = output.get("text").toString();
            }
        }
        if (check != null) {
            out.println("Verfügbarkeit, Stand " + STAND.format(Instant.parse(check.get("geprueft_am").toString())
                    .atZone(ZoneId.of(platform.settings().agentTimezone()))) + ":");
            out.println();
            out.print(check.get("tabelle"));
            out.println();
            List<Map<String, Object>> changes = (List<Map<String, Object>>) check.get("aenderungen");
            if (changes == null || changes.isEmpty()) {
                out.println("Keine Änderungen seit dem letzten Lauf.");
            } else {
                out.println("Änderungen seit dem letzten Lauf:");
                for (Map<String, Object> change : changes) {
                    out.println("- " + change.get("ware") + " bei " + change.get("portal") + ": "
                            + change.get("beschreibung") + " (" + change.get("vorher") + " → " + change.get("nachher") + ")");
                }
            }
        }
        if (report != null) {
            out.println();
            out.println("Bericht:");
            out.println(report);
        }
        Map<String, Object> usage = (Map<String, Object>) data.get("usage");
        if (((Number) usage.get("total_tokens")).longValue() > 0) {
            out.println();
            out.println("Tokenverbrauch: " + usage.get("total_tokens"));
            long microUsd = ((Number) usage.get("cost_microusd")).longValue();
            long microEur = ((Number) usage.get("cost_microeur")).longValue();
            if (microUsd > 0) {
                out.println(String.format(Locale.ROOT, "Kosten: %.6f USD", microUsd / 1_000_000.0));
            }
            if (microEur > 0) {
                out.println(String.format(Locale.ROOT, "Kosten: %.6f EUR", microEur / 1_000_000.0));
            }
        }
        if ("failed".equals(data.get("status"))) {
            err.println("Fehler: " + data.get("error"));
            return 1;
        }
        return 0;
    }
}
