package com.zuluindustries.agenten.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Prüft alle konfigurierten Waren in allen aktiven Portalen, speichert die
 * Ergebnisse und ermittelt die zu meldenden Änderungen. Reiner Code ohne KI;
 * ein Agent kommt erst beim Bericht ins Spiel.
 *
 * <p>Portale werden parallel abgefragt (je Portal ein virtueller Thread), die Waren
 * eines Portals nacheinander mit der konfigurierten Pause {@code delay_ms}.</p>
 */
public final class AvailabilityMonitor {

    /** Name der Workflow-Aktion, die einen Lauf ausführt. */
    public static final String ACTION = "b2b_verfuegbarkeit_pruefen";
    /** Mitgelieferter Workflow: prüfen, vergleichen, bei Änderungen Bericht erstellen. */
    public static final String WORKFLOW = "b2b_verfuegbarkeit";

    private static final Logger LOG = LoggerFactory.getLogger(AvailabilityMonitor.class);

    private final MonitorConfig config;
    private final ConnectorRegistry connectors;
    private final MonitorStore store;
    private final Function<String, String> environment;
    private final Clock clock;

    public AvailabilityMonitor(MonitorConfig config, ConnectorRegistry connectors, MonitorStore store,
                               Function<String, String> environment, Clock clock) {
        for (Map.Entry<String, MonitorConfig.PortalConfig> portal : config.portals().entrySet()) {
            if (!connectors.contains(portal.getValue().connector())) {
                throw new IllegalArgumentException("Portal '" + portal.getKey() + "': unbekannter Connector-Typ '"
                        + portal.getValue().connector() + "'. Verfügbar: " + String.join(", ", connectors.types()));
            }
        }
        this.config = config;
        this.connectors = connectors;
        this.store = store;
        this.environment = environment;
        this.clock = clock;
    }

    public MonitorConfig config() {
        return config;
    }

    public MonitorStore store() {
        return store;
    }

    /** Führt einen vollständigen Lauf aus. */
    public MonitorReport run() {
        String runId = UUID.randomUUID().toString().replace("-", "");
        Instant checkedAt = clock.instant();

        Map<String, List<MonitorConfig.ProductConfig>> byPortal = new LinkedHashMap<>();
        for (String portal : config.portals().keySet()) {
            if (config.portals().get(portal).enabled()) {
                byPortal.put(portal, new ArrayList<>());
            }
        }
        for (MonitorConfig.ProductConfig product : config.products()) {
            for (String portal : product.portals().keySet()) {
                List<MonitorConfig.ProductConfig> products = byPortal.get(portal);
                if (products != null) {
                    products.add(product);
                }
            }
        }

        List<CheckResult> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<CheckResult>>> futures = new ArrayList<>();
            for (Map.Entry<String, List<MonitorConfig.ProductConfig>> entry : byPortal.entrySet()) {
                futures.add(executor.submit(() -> checkPortal(entry.getKey(), entry.getValue())));
            }
            for (Future<List<CheckResult>> future : futures) {
                results.addAll(future.get());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Monitor-Lauf wurde unterbrochen.", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("Monitor-Lauf fehlgeschlagen: " + error.getCause().getMessage(), error);
        }
        // Ausgabe in Konfigurationsreihenfolge: Ware, dann Portal.
        List<String> productOrder = config.products().stream().map(MonitorConfig.ProductConfig::id).toList();
        List<String> portalOrder = List.copyOf(config.portals().keySet());
        results.sort(Comparator.<CheckResult>comparingInt(result -> productOrder.indexOf(result.productId()))
                .thenComparingInt(result -> portalOrder.indexOf(result.portal())));

        List<Change> changes = new ArrayList<>();
        for (CheckResult result : results) {
            for (Change change : ChangeDetector.detect(result,
                    store.lastKnown(result.productId(), result.portal(), result.sku()),
                    store.last(result.productId(), result.portal(), result.sku()))) {
                if (config.notifications().reports(change.type())) {
                    changes.add(change);
                }
            }
        }
        store.save(runId, results);
        LOG.info("Monitor-Lauf {} abgeschlossen: {} Prüfungen, {} Änderungen", runId, results.size(), changes.size());
        return new MonitorReport(runId, checkedAt, results, changes, config);
    }

    /** Workflow-Aktion für {@link #ACTION}. */
    public Map<String, Object> runAsAction(Map<String, Object> context, Map<String, Object> parameters) {
        return run().toMap();
    }

    private List<CheckResult> checkPortal(String portalKey, List<MonitorConfig.ProductConfig> products)
            throws InterruptedException {
        MonitorConfig.PortalConfig portal = config.portals().get(portalKey);
        List<CheckResult> results = new ArrayList<>();
        PortalConnector connector;
        try {
            connector = connectors.get(portal.connector()).create(portal, credentials(portal));
        } catch (RuntimeException error) {
            // Portal nicht nutzbar (z. B. fehlende Zugangsdaten): alle Waren als unbekannt melden.
            for (MonitorConfig.ProductConfig product : products) {
                results.add(CheckResult.failed(product.id(), portalKey, product.portals().get(portalKey).sku(),
                        message(error), clock.instant()));
            }
            return results;
        }
        for (int i = 0; i < products.size(); i++) {
            if (i > 0 && portal.delayMs() > 0) {
                Thread.sleep(Duration.ofMillis(portal.delayMs()));
            }
            MonitorConfig.ProductConfig product = products.get(i);
            String sku = product.portals().get(portalKey).sku();
            try {
                PortalOffer offer = connector.check(sku);
                if (offer == null) {
                    throw new IllegalStateException("Der Connector hat kein Ergebnis geliefert.");
                }
                results.add(CheckResult.of(product.id(), portalKey, sku, offer, clock.instant()));
            } catch (InterruptedException error) {
                throw error;
            } catch (Exception error) {
                LOG.warn("Abfrage fehlgeschlagen portal={} sku={} error={}", portalKey, sku, message(error));
                results.add(CheckResult.failed(product.id(), portalKey, sku, message(error), clock.instant()));
            }
        }
        return results;
    }

    private Map<String, String> credentials(MonitorConfig.PortalConfig portal) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : portal.credentials().entrySet()) {
            String value = environment.apply(entry.getValue());
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Umgebungsvariable " + entry.getValue() + " ist nicht gesetzt.");
            }
            values.put(entry.getKey(), value);
        }
        return values;
    }

    private static String message(Exception error) {
        return error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage() : error.getClass().getSimpleName();
    }
}
