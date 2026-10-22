package com.zuluindustries.agenten.monitor;

import com.zuluindustries.agenten.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailabilityMonitorTest {

    @TempDir
    Path directory;

    final Map<String, String> environment = new HashMap<>();
    final Map<String, Object> remote = new HashMap<>();
    final List<Map<String, String>> credentialsSeen = new ArrayList<>();

    static final String CONFIG = """
            portals:
              lager:
                name: Lager
                connector: demo
                delay_ms: 0
                settings:
                  items:
                    "4711": {available: false, price: "29.90", currency: EUR}
                    "4712": {available: true, quantity: 3, price: "12.345", currency: EUR, delivery: 2 Tage}
              api:
                name: API-Portal
                connector: fake
                delay_ms: 0
                credentials: {api_key: API_PORTAL_KEY}
              aus:
                connector: fake
                enabled: false
            products:
              - id: produkt_a
                name: Produkt A
                portals:
                  lager: {sku: "4711"}
                  api: {sku: A-1}
                  aus: {sku: X}
              - id: produkt_b
                name: Produkt B
                portals:
                  lager: {sku: "4712"}
            """;

    private AvailabilityMonitor monitor(String yaml) {
        ConnectorRegistry connectors = ConnectorRegistry.defaults().register("fake", (portal, credentials) -> {
            credentialsSeen.add(credentials);
            return sku -> {
                Object value = remote.get(sku);
                if (value instanceof RuntimeException error) {
                    throw error;
                }
                return (PortalOffer) value;
            };
        });
        return new AvailabilityMonitor(MonitorConfig.parse(yaml), connectors, new MonitorStore(directory.resolve("m.db")),
                environment::get, Fixtures.FIXED_CLOCK);
    }

    @Test
    void checksEnabledPortalsAndDetectsChangesAcrossRuns() {
        environment.put("API_PORTAL_KEY", "geheim");
        remote.put("A-1", new PortalOffer(true, 10, new BigDecimal("30.00"), "EUR", null));

        MonitorReport first = monitor(CONFIG).run();

        assertEquals(List.of("produkt_a/lager", "produkt_a/api", "produkt_b/lager"),
                first.results().stream().map(result -> result.productId() + "/" + result.portal()).toList());
        assertTrue(first.changes().isEmpty());
        assertEquals(List.of(Map.of("api_key", "geheim")), credentialsSeen);
        assertEquals(new BigDecimal("12.345"), new MonitorStore(directory.resolve("m.db"))
                .last("produkt_b", "lager", "4712").price());

        String changed = CONFIG.replace("\"4711\": {available: false", "\"4711\": {available: true");
        remote.put("A-1", new IllegalStateException("Login abgelaufen"));
        MonitorReport second = monitor(changed).run();

        assertEquals(Availability.UNKNOWN, second.results().get(1).availability());
        assertEquals("Login abgelaufen", second.results().get(1).error());
        assertEquals(List.of(Change.Type.BACK_IN_STOCK, Change.Type.CHECK_FAILED),
                second.changes().stream().map(Change::type).toList());
        assertEquals(1, second.count(Availability.UNKNOWN));

        Map<String, Object> data = second.toMap();
        assertEquals(true, data.get("has_changes"));
        assertEquals(Map.of("geprueft", 3, "verfuegbar", 2L, "nicht_verfuegbar", 0L, "unbekannt", 1L),
                data.get("zusammenfassung"));
        String table = data.get("tabelle").toString();
        assertTrue(table.contains("Produkt A  API-Portal  unbekannt (Login abgelaufen)"), table);
        assertTrue(table.contains("12,345 EUR"), table);
        assertEquals(3, new MonitorStore(directory.resolve("m.db")).results(second.runId()).size());
    }

    @Test
    void missingCredentialsMakeResultsUnknownInsteadOfUnavailable() {
        MonitorReport report = monitor(CONFIG).run();

        CheckResult api = report.results().get(1);
        assertEquals(Availability.UNKNOWN, api.availability());
        assertEquals("Umgebungsvariable API_PORTAL_KEY ist nicht gesetzt.", api.error());
        assertTrue(credentialsSeen.isEmpty());
    }

    @Test
    void notificationsFilterChanges() {
        environment.put("API_PORTAL_KEY", "geheim");
        remote.put("A-1", new PortalOffer(true, null, new BigDecimal("30"), "EUR", null));
        String quiet = CONFIG + "notifications: {on_price_change: false}\n";
        monitor(quiet).run();

        remote.put("A-1", new PortalOffer(true, null, new BigDecimal("25"), "EUR", null));
        assertTrue(monitor(quiet).run().changes().isEmpty());
        remote.put("A-1", new PortalOffer(true, null, new BigDecimal("20"), "EUR", null));
        assertFalse(monitor(CONFIG).run().changes().isEmpty());
    }

    @Test
    void demoConnectorReportsMissingItemsAsUnknown() {
        environment.put("API_PORTAL_KEY", "geheim");
        remote.put("A-1", new PortalOffer(false, null, null, null, null));
        MonitorReport report = monitor(CONFIG.replace("\"4712\": {available: true", "\"9999\": {available: true")).run();

        CheckResult missing = report.results().get(2);
        assertEquals(Availability.UNKNOWN, missing.availability());
        assertTrue(missing.error().contains("4712"), missing.error());
    }

    @Test
    void rejectsUnknownConnectorType() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> monitor(CONFIG.replace("connector: fake\n    delay_ms", "connector: browser\n    delay_ms")));
        assertTrue(error.getMessage().contains("unbekannter Connector-Typ 'browser'"), error.getMessage());
    }
}
