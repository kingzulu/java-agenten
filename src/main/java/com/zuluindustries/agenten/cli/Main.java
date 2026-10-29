package com.zuluindustries.agenten.cli;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.agent.MemoryConfig;
import com.zuluindustries.agenten.builder.AgentBuilder;
import com.zuluindustries.agenten.builder.AgentCreator;
import com.zuluindustries.agenten.builder.AgentTemplates;
import com.zuluindustries.agenten.core.AgentPermissionException;
import com.zuluindustries.agenten.core.AgentResult;
import com.zuluindustries.agenten.core.Conversation;
import com.zuluindustries.agenten.core.ModelCall;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.model.CostCalculator;
import com.zuluindustries.agenten.model.ModelCatalog;
import com.zuluindustries.agenten.model.ModelProfile;
import com.zuluindustries.agenten.monitor.AvailabilityMonitor;
import com.zuluindustries.agenten.monitor.MonitorConfig;
import com.zuluindustries.agenten.skill.Skill;
import com.zuluindustries.agenten.util.Environment;
import com.zuluindustries.agenten.util.Json;
import com.zuluindustries.agenten.workflow.WorkflowRun;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kommandozeile; entspricht {@code main.py}.
 *
 * <pre>
 *   agenten "&lt;frage&gt;"
 *   agenten &lt;agent&gt; "&lt;aufgabe&gt;"
 *   agenten &lt;agent&gt;                      (interaktiver Modus)
 *   agenten --show-config &lt;agent&gt;
 *   agenten --workflow &lt;key&gt; '&lt;json-eingabe&gt;'
 *   agenten --monitor                      (ein Lauf des B2B-Verfügbarkeitsmonitors)
 * </pre>
 */
public final class Main {

    /** Ab diesem Alter des Preisstands weist die CLI auf einen Abgleich hin. */
    static final int PRICE_REVIEW_DAYS = 90;

    private static final DateTimeFormatter STAND = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    Main() {
    }

    public static void main(String[] args) {
        Environment environment = Environment.system(dotEnvPath());
        Settings settings = Settings.load(environment);
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", logLevel(settings.logLevel()));
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        Platform platform;
        try {
            platform = Platform.builder(settings, environment).build();
        } catch (IllegalArgumentException error) {
            // Fehlerhafte Konfiguration: verständliche Meldung statt Stacktrace.
            err.println("Fehler: " + error.getMessage());
            err.println("Das Projektverzeichnis ist " + settings.projectRoot().toAbsolutePath().normalize()
                    + " (änderbar über AGENTEN_HOME).");
            System.exit(1);
            return;
        }
        int exitCode = new Main().run(platform, args, out, err);
        System.exit(exitCode);
    }

    int run(Platform platform, String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            printUsage(platform, out);
            return 1;
        }

        if ("--show-config".equals(args[0])) {
            if (args.length != 2) {
                out.println("Fehler: Bitte einen Agenten angeben.");
                out.println("Aufruf: agenten --show-config <agent>");
                return 1;
            }
            try {
                AgentDefinition agent = platform.agents().get(args[1], platform.settings().defaultModelProfile());
                out.print(Json.DEFINITIONS_YAML.writeValueAsString(agent));
                return 0;
            } catch (IllegalArgumentException | IOException error) {
                out.println("Fehler: " + error.getMessage());
                out.println("Verfügbare Agenten: " + String.join(", ", platform.agents().names()));
                return 1;
            }
        }

        if ("--workflow".equals(args[0])) {
            return runWorkflow(platform, args, out, err);
        }

        if ("--monitor".equals(args[0])) {
            if (args.length != 1) {
                err.println("Aufruf: agenten --monitor");
                return 1;
            }
            return runMonitor(platform, out, err);
        }

        String agentName;
        String userInput;
        boolean interactive;
        if (args.length == 1) {
            if (platform.agents().contains(args[0])) {
                agentName = args[0];
                userInput = null;
                interactive = true;
            } else {
                agentName = null;
                userInput = args[0];
                interactive = false;
            }
        } else {
            agentName = args[0];
            userInput = args[1];
            interactive = false;
        }

        if (!interactive && userInput.isBlank()) {
            err.println("Fehler: Die Eingabe ist leer.");
            return 1;
        }

        AgentDefinition agent;
        try {
            agent = platform.agents().get(agentName, platform.settings().defaultModelProfile());
        } catch (IllegalArgumentException error) {
            out.println("Fehler: " + error.getMessage());
            out.println("Verfügbare Agenten: " + String.join(", ", platform.agents().names()));
            return 1;
        }

        ModelProfile profile = platform.profiles().get(agent.model().profile());
        out.println("Provider: " + profile.provider());
        out.println("Modell: " + profile.model());

        List<String> allowedTools = platform.runner().runtime().policy().effectiveAllowedTools(agent);
        List<Skill> skills = platform.runner().runtime().capabilities().prepare(allowedTools, agent.skills()).skills();
        if (!skills.isEmpty()) {
            out.println("Verfügbare Skills:");
            out.println();
            for (Skill skill : skills) {
                out.println("- " + skill.name() + ": " + skill.description());
            }
        }

        if (!interactive) {
            Outcome outcome = runSelected(platform, agent, userInput, null, out, err);
            if (outcome.result() == null) {
                return 1;
            }
            printCosts(platform, outcome.result(), out);
            return outcome.definitionError() ? 1 : 0;
        }

        out.println();
        out.println("Interaktiver Modus gestartet.");
        out.println("Mit 'exit' oder 'quit' beenden.");
        MemoryConfig memory = agent.context().memory();
        Conversation conversation = !memory.enabled() || MemoryConfig.SESSION.equals(memory.scope())
                ? new Conversation() : null;
        long sessionIn = 0;
        long sessionOut = 0;
        long sessionTotal = 0;
        Map<String, Double> sessionCosts = new LinkedHashMap<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            out.println();
            out.print("Du: ");
            out.flush();
            String line;
            try {
                line = reader.readLine();
            } catch (IOException error) {
                break;
            }
            if (line == null) {
                out.println();
                break;
            }
            line = line.strip();
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                break;
            }
            if (line.isEmpty()) {
                continue;
            }
            AgentResult result = runSelected(platform, agent, line, conversation, out, err).result();
            if (result == null) {
                continue;
            }
            List<CostCalculator.Costs> costs = printCosts(platform, result, out);
            sessionIn += result.inputTokens();
            sessionOut += result.outputTokens();
            sessionTotal += result.totalTokens();
            for (CostCalculator.Costs item : costs) {
                sessionCosts.merge(item.currency(), item.totalCost(), Double::sum);
            }
        }
        out.println();
        out.println("Tokenverbrauch der Session:");
        out.println("Input:  " + sessionIn);
        out.println("Output: " + sessionOut);
        out.println("Gesamt: " + sessionTotal);
        out.println();
        if (sessionCosts.isEmpty()) {
            out.println("Kosten der Session: 0");
        }
        sessionCosts.forEach((currency, amount) ->
                out.println(String.format(Locale.ROOT, "Kosten der Session: %.8f %s", amount, currency)));
        return 0;
    }

    /** Ergebnis eines Agentenlaufs; {@code result} ist {@code null}, wenn der Lauf nicht starten konnte. */
    private record Outcome(AgentResult result, boolean definitionError) {
    }

    /**
     * Führt den gewählten Agenten aus und gibt seinen Text aus. Wie in {@code main.py}
     * erzeugt der agent_creator_agent zuerst über den prompt_agent einen Prompt und
     * speichert seine Definition anschließend; der agent_builder speichert selbst.
     */
    private static Outcome runSelected(Platform platform, AgentDefinition agent, String input,
                                       Conversation conversation, PrintStream out, PrintStream err) {
        int maxSteps = platform.settings().maxAgentSteps();
        try {
            if (AgentBuilder.CREATOR_AGENT.equals(agent.id())) {
                AgentCreator.Result created = AgentCreator.run(
                        (nested, text) -> platform.runner().run(nested, text, maxSteps),
                        platform.agents().get(AgentBuilder.PROMPT_AGENT, null), agent, input);
                AgentResult result = created.result();
                out.println();
                out.println(result.text());
                if (result.failed()) {
                    return new Outcome(result, true);
                }
                try {
                    Path saved = AgentTemplates.save(result.text(), created.generatedPrompt(),
                            platform.settings().projectRoot().resolve("agents").resolve("definitions"));
                    out.println();
                    out.println("Definition gespeichert: " + saved);
                    return new Outcome(result, false);
                } catch (IllegalArgumentException | UncheckedIOException error) {
                    err.println("Definition konnte nicht gespeichert werden: " + error.getMessage());
                    return new Outcome(result, true);
                }
            }
            AgentResult result = platform.runAgent(agent, input, conversation);
            out.println();
            out.println(result.text());
            return new Outcome(result, AgentBuilder.AGENT_ID.equals(agent.id()) && result.failed());
        } catch (IllegalArgumentException | AgentPermissionException error) {
            err.println("Fehler: " + error.getMessage());
            return new Outcome(null, true);
        }
    }

    private int runWorkflow(Platform platform, String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2 || args.length > 3) {
            err.println("Aufruf: agenten --workflow <key> '<json-eingabe>'");
            err.println("Verfügbare Workflows: " + String.join(", ", platform.workflows().definitions().keySet()));
            return 1;
        }
        Map<String, Object> input;
        try {
            input = args.length == 3 ? Json.readMap(args[2]) : Map.of();
        } catch (RuntimeException error) {
            err.println("Fehler: Die Workflow-Eingabe ist kein valides JSON-Objekt.");
            return 1;
        }
        try {
            WorkflowRun run = platform.workflows().start(args[1], input);
            out.println(Json.writePretty(platform.workflows().serialize(run)));
            return "failed".equals(run.status.key()) ? 1 : 0;
        } catch (IllegalArgumentException error) {
            err.println("Fehler: " + error.getMessage());
            return 1;
        }
    }

    /**
     * Ein Lauf des Verfügbarkeitsmonitors über den Workflow {@link AvailabilityMonitor#WORKFLOW}.
     * Die Übersicht wird auch dann ausgegeben, wenn der anschließende Bericht scheitert.
     */
    @SuppressWarnings("unchecked")
    private int runMonitor(Platform platform, PrintStream out, PrintStream err) {
        if (platform.monitor() == null) {
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

    private static List<CostCalculator.Costs> printCosts(Platform platform, AgentResult result, PrintStream out) {
        long cached = result.modelCalls().stream().mapToLong(ModelCall::cachedInputTokens).sum();
        out.println();
        out.println("Tokenverbrauch:");
        out.println("Input:  " + result.inputTokens() + (cached > 0 ? " (davon aus dem Cache: " + cached + ")" : ""));
        out.println("Output: " + result.outputTokens());
        out.println("Gesamt: " + result.totalTokens());
        if (result.modelCalls().isEmpty()) {
            return List.of();
        }
        List<CostCalculator.Costs> costs;
        try {
            costs = platform.costs().calculate(result.modelCalls());
        } catch (IllegalArgumentException error) {
            out.println();
            out.println("Kosten: " + error.getMessage());
            return List.of();
        }
        for (CostCalculator.Costs item : costs) {
            out.println();
            out.println("Kosten:");
            out.println(String.format(Locale.ROOT, "Input:  %.8f %s", item.inputCost(), item.currency()));
            out.println(String.format(Locale.ROOT, "Output: %.8f %s", item.outputCost(), item.currency()));
            out.println(String.format(Locale.ROOT, "Gesamt: %.8f %s", item.totalCost(), item.currency()));
        }
        LocalDate asOf = platform.catalog().pricesAsOf();
        out.println("Preisstand: " + asOf + " (" + ModelCatalog.RELATIVE_PATH + ")");
        if (asOf.isBefore(platform.costs().today().minusDays(PRICE_REVIEW_DAYS))) {
            out.println("Hinweis: Der Preisstand ist älter als " + PRICE_REVIEW_DAYS
                    + " Tage. Bitte die Preise mit den Anbieterseiten abgleichen.");
        }
        return costs;
    }

    private static void printUsage(Platform platform, PrintStream out) {
        out.println("Aufrufmöglichkeiten:");
        out.println();
        out.println("  agenten \"<frage>\"");
        out.println("  agenten <agent> \"<frage>\"");
        out.println("  agenten <agent>");
        out.println("  agenten --show-config <agent>");
        out.println("  agenten --workflow <key> '<json-eingabe>'");
        out.println("  agenten --monitor");
        out.println();
        out.println("Verfügbare Agenten: " + String.join(", ", platform.agents().names()));
    }

    private static Path dotEnvPath() {
        String home = System.getenv("AGENTEN_HOME");
        return Path.of(home == null || home.isBlank() ? "." : home).resolve(".env");
    }

    private static String logLevel(String level) {
        return switch (level) {
            case "DEBUG" -> "debug";
            case "WARNING", "WARN" -> "warn";
            case "ERROR", "CRITICAL" -> "error";
            default -> "info";
        };
    }
}
