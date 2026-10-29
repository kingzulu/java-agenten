package com.zuluindustries.agenten.monitor;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.extension.CliCommand;
import com.zuluindustries.agenten.extension.PlatformExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Bindet den B2B-Verfügbarkeitsmonitor in die Plattform ein: den Bericht-Agenten
 * {@code verfuegbarkeits_bericht}, den Workflow {@value AvailabilityMonitor#WORKFLOW}, die
 * Workflow-Aktion {@value AvailabilityMonitor#ACTION} und den Befehl {@code --monitor}.
 *
 * <p>Ohne {@code config/monitor.yaml} im Projektverzeichnis bleibt der Monitor aus; Agent
 * und Workflow sind dann trotzdem registriert, die Aktion meldet aber einen Fehler.</p>
 */
public final class MonitorExtension implements PlatformExtension {

    public static final String NAME = "monitor";
    public static final String REPORT_AGENT = "verfuegbarkeits_bericht";

    private final ConnectorRegistry connectors;
    private AvailabilityMonitor monitor;

    /** Für den {@link java.util.ServiceLoader}: nur die mitgelieferten Zugriffsarten. */
    public MonitorExtension() {
        this(ConnectorRegistry.defaults());
    }

    /** @param connectors Zugriffsarten für die Portale */
    public MonitorExtension(ConnectorRegistry connectors) {
        this.connectors = connectors;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, String> agentDefinitions() {
        return Map.of(REPORT_AGENT, PlatformExtension.resource(getClass(), "agents/" + REPORT_AGENT + ".yaml"));
    }

    @Override
    public Map<String, String> workflowDefinitions() {
        return Map.of(AvailabilityMonitor.WORKFLOW,
                PlatformExtension.resource(getClass(), "workflows/" + AvailabilityMonitor.WORKFLOW + ".yaml"));
    }

    @Override
    public void install(Platform platform) {
        Path root = platform.settings().projectRoot();
        MonitorConfig config = MonitorConfig.loadIfPresent(root);
        if (config != null) {
            monitor = new AvailabilityMonitor(config, connectors,
                    new MonitorStore(root.resolve(config.monitor().database())), platform.environment(),
                    platform.clock());
        }
        platform.actions().register(AvailabilityMonitor.ACTION, (context, parameters) -> {
            if (monitor == null) {
                throw new IllegalStateException(MonitorConfig.RELATIVE_PATH
                        + " fehlt; der Verfügbarkeitsmonitor ist nicht konfiguriert.");
            }
            return monitor.runAsAction(context, parameters);
        });
    }

    @Override
    public List<CliCommand> commands() {
        return List.of(new MonitorCommand(this));
    }

    /** Der konfigurierte Monitor; {@code null}, wenn {@code config/monitor.yaml} fehlt. */
    public AvailabilityMonitor monitor() {
        return monitor;
    }
}
