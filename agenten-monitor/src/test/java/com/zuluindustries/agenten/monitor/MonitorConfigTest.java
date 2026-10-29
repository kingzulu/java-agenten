package com.zuluindustries.agenten.monitor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorConfigTest {

    static final String VALID = """
            portals:
              portal_a:
                connector: demo
                credentials: {api_key: PORTAL_A_API_KEY}
              portal_b: {name: Portal B, connector: demo, enabled: false, delay_ms: 0}
            products:
              - id: produkt_001
                name: Produkt A
                ean: "1234567890123"
                portals:
                  portal_a: {sku: "4711"}
                  portal_b: {sku: ABC-123}
            """;

    @Test
    void appliesDefaults() {
        MonitorConfig config = MonitorConfig.parse(VALID);

        assertEquals("b2b-verfuegbarkeit", config.monitor().name());
        assertEquals(30, config.monitor().intervalMinutes());
        assertEquals("data/monitor.db", config.monitor().database());
        assertEquals(1000L, config.portals().get("portal_a").delayMs());
        assertTrue(config.portals().get("portal_a").enabled());
        assertFalse(config.portals().get("portal_b").enabled());
        assertEquals("portal_a", config.portals().get("portal_a").displayName("portal_a"));
        assertEquals("Portal B", config.portals().get("portal_b").displayName("portal_b"));
        assertEquals("4711", config.products().getFirst().portals().get("portal_a").sku());
        assertTrue(config.notifications().reports(Change.Type.BACK_IN_STOCK));
        assertTrue(config.notifications().reports(Change.Type.PRICE_CHANGED));
        assertFalse(config.notifications().reports(Change.Type.DELIVERY_CHANGED));
        assertTrue(config.notifications().reports(Change.Type.CHECK_FAILED));
    }

    @Test
    void rejectsUnknownFields() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MonitorConfig.parse(VALID + "notifications: {on_stock_chnage: true}\n"));
        assertTrue(error.getMessage().contains("on_stock_chnage"), error.getMessage());
    }

    @Test
    void rejectsUnknownPortalReference() {
        String yaml = VALID.replace("portal_b: {sku: ABC-123}", "portal_c: {sku: ABC-123}");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> MonitorConfig.parse(yaml));
        assertTrue(error.getMessage().contains("unbekanntes Portal 'portal_c'"), error.getMessage());
    }

    @Test
    void rejectsSecretsInsteadOfVariableNames() {
        String yaml = VALID.replace("PORTAL_A_API_KEY", "sk-geheim123");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> MonitorConfig.parse(yaml));
        assertTrue(error.getMessage().contains("Namen einer Umgebungsvariablen"), error.getMessage());
    }

    @Test
    void rejectsDuplicateProductsAndMissingSku() {
        String duplicate = VALID + """
                  - id: produkt_001
                    portals: {portal_a: {sku: "1"}}
                """;
        assertThrows(IllegalArgumentException.class, () -> MonitorConfig.parse(duplicate));
        assertThrows(IllegalArgumentException.class, () -> MonitorConfig.parse(VALID.replace("{sku: \"4711\"}", "{}")));
        assertThrows(IllegalArgumentException.class, () -> MonitorConfig.parse("portals: {}\nproducts: []\n"));
    }

    @Test
    void missingFileMeansNoMonitor() {
        assertNull(MonitorConfig.loadIfPresent(Path.of("gibt-es-nicht")));
    }
}
