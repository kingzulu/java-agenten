package com.zuluindustries.agenten.monitor;

import java.math.BigDecimal;

/**
 * Was ein Portal zu einer Artikelnummer meldet. Optionale Angaben sind
 * {@code null}, wenn das Portal sie nicht liefert.
 *
 * @param available ob die Ware bestellbar ist
 * @param quantity  gemeldeter Bestand
 * @param price     Preis je Einheit
 * @param currency  Währung des Preises, z. B. {@code EUR}
 * @param delivery  Lieferzeit im Wortlaut des Portals
 */
public record PortalOffer(boolean available, Integer quantity, BigDecimal price, String currency, String delivery) {

    public PortalOffer {
        if (price != null && price.signum() < 0) {
            throw new IllegalArgumentException("Ein Preis darf nicht negativ sein.");
        }
        if (price != null && (currency == null || currency.isBlank())) {
            throw new IllegalArgumentException("Ein Preis benötigt eine Währung.");
        }
        if (quantity != null && quantity < 0) {
            throw new IllegalArgumentException("Ein Bestand darf nicht negativ sein.");
        }
    }
}
