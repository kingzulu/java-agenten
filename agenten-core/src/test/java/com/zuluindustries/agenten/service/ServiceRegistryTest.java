package com.zuluindustries.agenten.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceRegistryTest {

    static ServiceManifest inspection() {
        return new ServiceManifest("inspection_reports", "Prüfberichte",
                "LLM-unterstützte Erstellung und Verwaltung von Prüfberichten.",
                List.of("inspection.reports.read", "inspection.reports.write"),
                List.of("inspection_report_generator"), List.of("inspection_report_generation"));
    }

    @Test
    void registersExplicitServices() {
        ServiceRegistry registry = new ServiceRegistry().register(inspection());

        assertEquals("Prüfberichte", registry.get("inspection_reports").name());
        assertEquals(1, registry.list().size());
        assertThrows(IllegalArgumentException.class, () -> registry.register(inspection()));
        assertThrows(IllegalArgumentException.class, () -> registry.get("fehlt"));
    }

    @Test
    void validatesManifest() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceManifest("Ungültig", "N", "D", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceManifest("ok", " ", "D", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceManifest("ok", "N", "D", List.of("a.b", "a.b"), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceManifest("ok", "N", "D", null, List.of("Agent-X"), null));
    }
}
