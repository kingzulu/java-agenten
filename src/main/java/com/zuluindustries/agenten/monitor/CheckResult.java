package com.zuluindustries.agenten.monitor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Ergebnis der Prüfung einer Ware in einem Portal. Bei {@link Availability#UNKNOWN}
 * enthält {@code error} den Grund, die übrigen Angaben sind leer.
 */
public record CheckResult(String productId, String portal, String sku, Availability availability, Integer quantity,
                          BigDecimal price, String currency, String delivery, String error, Instant checkedAt) {

    public CheckResult {
        if (availability == null) {
            throw new IllegalArgumentException("Ein Prüfergebnis benötigt eine Verfügbarkeit.");
        }
        if (availability == Availability.UNKNOWN && (error == null || error.isBlank())) {
            throw new IllegalArgumentException("Ein unbekanntes Ergebnis benötigt einen Fehlergrund.");
        }
    }

    public static CheckResult of(String productId, String portal, String sku, PortalOffer offer, Instant checkedAt) {
        return new CheckResult(productId, portal, sku,
                offer.available() ? Availability.AVAILABLE : Availability.UNAVAILABLE,
                offer.quantity(), offer.price(), offer.currency(), offer.delivery(), null, checkedAt);
    }

    public static CheckResult failed(String productId, String portal, String sku, String error, Instant checkedAt) {
        return new CheckResult(productId, portal, sku, Availability.UNKNOWN, null, null, null, null,
                error == null || error.isBlank() ? "Unbekannter Fehler" : error, checkedAt);
    }

    public boolean known() {
        return availability != Availability.UNKNOWN;
    }
}
