package com.zuluindustries.agenten.monitor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeDetectorTest {

    static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    static CheckResult offer(boolean available, String price, String delivery) {
        return CheckResult.of("p1", "portal_a", "4711",
                new PortalOffer(available, null, price == null ? null : new BigDecimal(price), price == null ? null : "EUR",
                        delivery), NOW);
    }

    static CheckResult failed() {
        return CheckResult.failed("p1", "portal_a", "4711", "Zeitüberschreitung", NOW);
    }

    @Test
    void firstCheckIsNoChange() {
        assertTrue(ChangeDetector.detect(offer(true, "1.00", null), null, null).isEmpty());
    }

    @Test
    void detectsStockPriceAndDeliveryChanges() {
        List<Change> changes = ChangeDetector.detect(offer(true, "29.90", "2 Tage"), offer(false, "31.00", "5 Tage"),
                offer(false, "31.00", "5 Tage"));

        assertEquals(List.of(Change.Type.BACK_IN_STOCK, Change.Type.PRICE_CHANGED, Change.Type.DELIVERY_CHANGED),
                changes.stream().map(Change::type).toList());
        assertEquals("31,00 EUR", changes.get(1).before());
        assertEquals("29,90 EUR", changes.get(1).after());

        assertEquals(Change.Type.OUT_OF_STOCK,
                ChangeDetector.detect(offer(false, null, null), offer(true, null, null), null).getFirst().type());
    }

    @Test
    void equalPricesWithDifferentScaleAreNoChange() {
        assertTrue(ChangeDetector.detect(offer(true, "29.9", null), offer(true, "29.90", null), null).isEmpty());
    }

    @Test
    void failedCheckIsNeverReportedAsUnavailable() {
        List<Change> changes = ChangeDetector.detect(failed(), offer(true, null, null), offer(true, null, null));

        assertEquals(1, changes.size());
        assertEquals(Change.Type.CHECK_FAILED, changes.getFirst().type());
        assertEquals("unbekannt (Zeitüberschreitung)", changes.getFirst().after());
        // Wiederholter Fehler wird nicht erneut gemeldet.
        assertTrue(ChangeDetector.detect(failed(), offer(true, null, null), failed()).isEmpty());
    }

    @Test
    void recoveryComparesWithLastKnownResult() {
        // Verfügbar → Fehler → verfügbar: keine Scheinänderung.
        assertTrue(ChangeDetector.detect(offer(true, null, null), offer(true, null, null), failed()).isEmpty());
        // Nicht verfügbar → Fehler → verfügbar: wieder verfügbar.
        assertEquals(Change.Type.BACK_IN_STOCK,
                ChangeDetector.detect(offer(true, null, null), offer(false, null, null), failed()).getFirst().type());
    }

    @Test
    void formatsPrices() {
        assertEquals("29,90 EUR", ChangeDetector.formatPrice(new BigDecimal("29.9"), "EUR"));
        assertEquals("0,125 USD", ChangeDetector.formatPrice(new BigDecimal("0.1250"), "USD"));
        assertEquals("100,00 EUR", ChangeDetector.formatPrice(new BigDecimal("1E+2"), "EUR"));
        assertEquals("–", ChangeDetector.formatPrice(null, "EUR"));
    }
}
