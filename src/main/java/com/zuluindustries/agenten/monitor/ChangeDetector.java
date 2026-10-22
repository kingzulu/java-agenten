package com.zuluindustries.agenten.monitor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Vergleicht ein Prüfergebnis mit der Historie. Rein deterministisch, ohne KI.
 *
 * <p>Verfügbarkeit, Preis und Lieferzeit werden mit dem letzten <em>bekannten</em>
 * Ergebnis verglichen, damit ein zwischenzeitlicher Abfragefehler keine
 * Scheinänderung erzeugt. Ein Abfragefehler wird nur gemeldet, wenn der vorige
 * Lauf noch erfolgreich war. Die erste Prüfung einer Ware ist keine Änderung.</p>
 */
public final class ChangeDetector {

    private ChangeDetector() {
    }

    /**
     * @param current   aktuelles Ergebnis
     * @param lastKnown letztes Ergebnis mit bekannter Verfügbarkeit oder {@code null}
     * @param last      letztes Ergebnis überhaupt oder {@code null}
     */
    public static List<Change> detect(CheckResult current, CheckResult lastKnown, CheckResult last) {
        List<Change> changes = new ArrayList<>();
        String product = current.productId();
        String portal = current.portal();
        if (!current.known()) {
            if (last != null && last.known()) {
                changes.add(new Change(Change.Type.CHECK_FAILED, product, portal,
                        last.availability().label(), Availability.UNKNOWN.label() + " (" + current.error() + ")"));
            }
            return changes;
        }
        if (lastKnown == null) {
            return changes;
        }
        if (lastKnown.availability() != current.availability()) {
            changes.add(new Change(current.availability() == Availability.AVAILABLE
                    ? Change.Type.BACK_IN_STOCK : Change.Type.OUT_OF_STOCK, product, portal,
                    lastKnown.availability().label(), current.availability().label()));
        }
        if (lastKnown.price() != null && current.price() != null
                && (lastKnown.price().compareTo(current.price()) != 0
                || !Objects.equals(lastKnown.currency(), current.currency()))) {
            changes.add(new Change(Change.Type.PRICE_CHANGED, product, portal,
                    formatPrice(lastKnown.price(), lastKnown.currency()), formatPrice(current.price(), current.currency())));
        }
        if (lastKnown.delivery() != null && current.delivery() != null
                && !lastKnown.delivery().equals(current.delivery())) {
            changes.add(new Change(Change.Type.DELIVERY_CHANGED, product, portal, lastKnown.delivery(), current.delivery()));
        }
        return changes;
    }

    /** Preis mit zwei Nachkommastellen und Komma, z. B. {@code 29,90 EUR}. */
    public static String formatPrice(BigDecimal price, String currency) {
        if (price == null) {
            return "–";
        }
        BigDecimal scaled = price.stripTrailingZeros();
        if (scaled.scale() < 2) {
            scaled = scaled.setScale(2);
        }
        return scaled.toPlainString().replace('.', ',') + (currency == null ? "" : " " + currency);
    }
}
