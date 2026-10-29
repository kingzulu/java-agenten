package com.zuluindustries.agenten.monitor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Test-Connector ohne echte Abfrage: liefert den Bestand, der unter
 * {@code settings.items} in der Konfiguration steht. Damit lassen sich Ablauf,
 * Historie und Berichte ausprobieren, bevor ein echtes Portal angebunden ist.
 *
 * <pre>
 * settings:
 *   items:
 *     "4711": {available: true, quantity: 120, price: "29.90", currency: EUR, delivery: 1-2 Werktage}
 *     "4712": {available: false}
 *     "4713": {error: Zeitüberschreitung}      # simuliert eine fehlgeschlagene Abfrage
 * </pre>
 */
public final class DemoConnector implements PortalConnector {

    public static final String TYPE = "demo";

    private static final Set<String> SETTINGS = Set.of("items");
    private static final Set<String> ITEM_FIELDS = Set.of("available", "quantity", "price", "currency", "delivery", "error");

    private final Map<?, ?> items;

    public DemoConnector(MonitorConfig.PortalConfig portal, Map<String, String> credentials) {
        for (String key : portal.settings().keySet()) {
            if (!SETTINGS.contains(key)) {
                throw new IllegalArgumentException("Unbekannte Einstellung '" + key + "' für den Demo-Connector.");
            }
        }
        Object configured = portal.settings().get("items");
        if (configured != null && !(configured instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("settings.items muss eine Zuordnung von Artikelnummern sein.");
        }
        this.items = configured == null ? Map.of() : (Map<?, ?>) configured;
    }

    @Override
    public PortalOffer check(String sku) {
        if (!(items.get(sku) instanceof Map<?, ?> item)) {
            throw new IllegalStateException("Artikelnummer " + sku + " ist im Testbestand nicht hinterlegt.");
        }
        for (Object key : item.keySet()) {
            if (!ITEM_FIELDS.contains(String.valueOf(key))) {
                throw new IllegalArgumentException("Unbekanntes Feld '" + key + "' im Testbestand von " + sku + ".");
            }
        }
        if (item.get("error") != null) {
            throw new IllegalStateException(String.valueOf(item.get("error")));
        }
        if (!(item.get("available") instanceof Boolean available)) {
            throw new IllegalArgumentException("Testbestand von " + sku + " benötigt 'available: true|false'.");
        }
        Object quantity = item.get("quantity");
        Object price = item.get("price");
        Object currency = item.get("currency");
        Object delivery = item.get("delivery");
        return new PortalOffer(available,
                quantity == null ? null : ((Number) quantity).intValue(),
                price == null ? null : new BigDecimal(price.toString()),
                currency == null ? null : currency.toString(),
                delivery == null ? null : delivery.toString());
    }
}
