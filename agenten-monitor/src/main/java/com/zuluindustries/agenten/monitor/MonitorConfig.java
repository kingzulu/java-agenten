package com.zuluindustries.agenten.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.zuluindustries.agenten.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Konfiguration des B2B-Verfügbarkeitsmonitors aus {@code config/monitor.yaml}:
 * Portale, Waren mit Artikelnummer je Portal und Benachrichtigungsregeln.
 *
 * <p>Die Datei wird streng geprüft; unbekannte Felder sind Fehler, damit sich
 * Tippfehler nicht still auswirken. Zugangsdaten stehen nie in der Datei, sondern
 * nur die Namen der Umgebungsvariablen, die sie enthalten.</p>
 */
public record MonitorConfig(Monitor monitor, Map<String, PortalConfig> portals, List<ProductConfig> products,
                            Notifications notifications) {

    public static final String RELATIVE_PATH = "config/monitor.yaml";

    private static final ObjectMapper STRICT_YAML = Json.DEFINITIONS_YAML.copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");

    public MonitorConfig {
        monitor = monitor == null ? new Monitor(null, null, null) : monitor;
        notifications = notifications == null ? new Notifications(null, null, null, null) : notifications;
        if (portals == null || portals.isEmpty()) {
            throw new IllegalArgumentException("Es ist kein Portal konfiguriert (portals).");
        }
        if (products == null || products.isEmpty()) {
            throw new IllegalArgumentException("Es ist keine Ware konfiguriert (products).");
        }
        portals = Collections.unmodifiableMap(new LinkedHashMap<>(portals));
        products = List.copyOf(products);
        for (String key : portals.keySet()) {
            if (!KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Ungültiger Portalschlüssel '" + key
                        + "' (erlaubt: Kleinbuchstaben, Ziffern, '_' und '-').");
            }
        }
        Set<String> ids = new HashSet<>();
        for (ProductConfig product : products) {
            if (!ids.add(product.id())) {
                throw new IllegalArgumentException("Ware '" + product.id() + "' ist mehrfach konfiguriert.");
            }
            for (String portal : product.portals().keySet()) {
                if (!portals.containsKey(portal)) {
                    throw new IllegalArgumentException("Ware '" + product.id() + "' verweist auf unbekanntes Portal '"
                            + portal + "'.");
                }
            }
        }
    }

    /** Lädt {@code config/monitor.yaml}; {@code null}, wenn die Datei fehlt. */
    public static MonitorConfig loadIfPresent(Path projectRoot) {
        Path path = projectRoot.resolve(RELATIVE_PATH);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return STRICT_YAML.readValue(path.toFile(), MonitorConfig.class);
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalArgumentException("Ungültige Monitor-Konfiguration '" + path + "': "
                    + describe(error), error);
        }
    }

    static MonitorConfig parse(String yaml) {
        try {
            return STRICT_YAML.readValue(yaml, MonitorConfig.class);
        } catch (IOException error) {
            throw new IllegalArgumentException(describe(error), error);
        }
    }

    /** Fehlermeldung mit Zeile und Spalte; bei Prüffehlern die eigene Meldung statt der Jackson-Hülle. */
    private static String describe(Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null && !(cause instanceof IllegalArgumentException)) {
            cause = cause.getCause();
        }
        String message;
        if (cause instanceof IllegalArgumentException && cause != error) {
            message = cause.getMessage();
        } else if (error instanceof UnrecognizedPropertyException unknown) {
            message = "Unbekanntes Feld '" + unknown.getPropertyName() + "'; erlaubt sind "
                    + unknown.getKnownPropertyIds();
        } else if (error instanceof JsonProcessingException json) {
            message = json.getOriginalMessage();
        } else {
            message = error.getMessage();
        }
        message = message == null ? "unbekannter Fehler" : message.lines().findFirst().orElse(message);
        if (error instanceof JsonProcessingException json && json.getLocation() != null
                && json.getLocation().getLineNr() > 0) {
            message += " (Zeile " + json.getLocation().getLineNr() + ", Spalte " + json.getLocation().getColumnNr() + ")";
        }
        return message;
    }

    /** Allgemeine Angaben; {@code interval_minutes} dokumentiert den Takt der externen Zeitsteuerung. */
    public record Monitor(String name, Integer intervalMinutes, String database) {

        public Monitor {
            name = name == null || name.isBlank() ? "b2b-verfuegbarkeit" : name;
            intervalMinutes = intervalMinutes == null ? 30 : intervalMinutes;
            database = database == null || database.isBlank() ? "data/monitor.db" : database;
            if (intervalMinutes < 1) {
                throw new IllegalArgumentException("monitor.interval_minutes muss mindestens 1 sein.");
            }
        }
    }

    /**
     * Ein Portal. {@code connector} wählt die Zugriffsart, {@code settings} enthält
     * deren eigene Einstellungen, {@code credentials} ordnet Namen wie {@code api_key}
     * einer Umgebungsvariablen zu. {@code delay_ms} ist die Pause zwischen zwei
     * Abfragen desselben Portals.
     */
    public record PortalConfig(String name, String connector, Boolean enabled, Long delayMs,
                               Map<String, String> credentials, Map<String, Object> settings) {

        public PortalConfig {
            if (connector == null || connector.isBlank()) {
                throw new IllegalArgumentException("Ein Portal benötigt 'connector'.");
            }
            enabled = enabled == null || enabled;
            delayMs = delayMs == null ? 1000L : delayMs;
            if (delayMs < 0) {
                throw new IllegalArgumentException("delay_ms darf nicht negativ sein.");
            }
            credentials = credentials == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(credentials));
            settings = settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
            for (Map.Entry<String, String> entry : credentials.entrySet()) {
                if (entry.getValue() == null || !ENV_NAME.matcher(entry.getValue()).matches()) {
                    throw new IllegalArgumentException("credentials." + entry.getKey()
                            + " erwartet den Namen einer Umgebungsvariablen (z. B. PORTAL_A_API_KEY), nicht den Wert.");
                }
            }
        }

        /** Anzeigename; ohne Angabe der Portalschlüssel. */
        public String displayName(String key) {
            return name == null || name.isBlank() ? key : name;
        }
    }

    /** Eine Ware mit ihrer Artikelnummer je Portal. */
    public record ProductConfig(String id, String name, String ean, Map<String, ProductPortal> portals) {

        public ProductConfig {
            if (id == null || !KEY.matcher(id).matches()) {
                throw new IllegalArgumentException("Ungültige Waren-ID '" + id
                        + "' (erlaubt: Kleinbuchstaben, Ziffern, '_' und '-').");
            }
            name = name == null || name.isBlank() ? id : name;
            if (portals == null || portals.isEmpty()) {
                throw new IllegalArgumentException("Ware '" + id + "' ist keinem Portal zugeordnet.");
            }
            portals = Collections.unmodifiableMap(new LinkedHashMap<>(portals));
        }
    }

    /** Artikelnummer einer Ware in einem Portal. */
    public record ProductPortal(String sku) {

        public ProductPortal {
            if (sku == null || sku.isBlank()) {
                throw new IllegalArgumentException("Eine Portalzuordnung benötigt 'sku'.");
            }
        }
    }

    /** Welche Änderungen gemeldet werden. Neue Abfragefehler werden standardmäßig gemeldet. */
    public record Notifications(Boolean onStockChange, Boolean onPriceChange, Boolean onDeliveryChange,
                                Boolean onCheckFailure) {

        public Notifications {
            onStockChange = onStockChange == null || onStockChange;
            onPriceChange = onPriceChange == null || onPriceChange;
            onDeliveryChange = onDeliveryChange != null && onDeliveryChange;
            onCheckFailure = onCheckFailure == null || onCheckFailure;
        }

        public boolean reports(Change.Type type) {
            return switch (type) {
                case BACK_IN_STOCK, OUT_OF_STOCK -> onStockChange;
                case PRICE_CHANGED -> onPriceChange;
                case DELIVERY_CHANGED -> onDeliveryChange;
                case CHECK_FAILED -> onCheckFailure;
            };
        }
    }
}
