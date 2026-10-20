package com.zuluindustries.agenten.monitor;

import java.util.Map;

/**
 * Fragt ein Portal ab. Ein Connector ist ein Werkzeug, kein KI-Agent.
 *
 * <p>Kann ein Connector die Verfügbarkeit nicht sicher feststellen (Netzwerkfehler,
 * Login abgelaufen, unbekannte Seitenstruktur), wirft er eine Ausnahme. Der Monitor
 * wertet das als {@link Availability#UNKNOWN} mit Fehlergrund und nie als
 * „nicht verfügbar“.</p>
 */
@FunctionalInterface
public interface PortalConnector {

    PortalOffer check(String sku) throws Exception;

    /** Erzeugt einen Connector für ein konfiguriertes Portal. */
    @FunctionalInterface
    interface Factory {

        /**
         * @param portal      Konfiguration des Portals
         * @param credentials aufgelöste Zugangsdaten, Schlüssel wie unter {@code credentials} konfiguriert
         */
        PortalConnector create(MonitorConfig.PortalConfig portal, Map<String, String> credentials);
    }
}
