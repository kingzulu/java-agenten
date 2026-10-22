package com.zuluindustries.agenten.monitor;

import java.util.Locale;

/** Eine erkannte Änderung gegenüber dem letzten Lauf. */
public record Change(Type type, String productId, String portal, String before, String after) {

    /** Art der Änderung. */
    public enum Type {
        /** War nicht verfügbar, ist jetzt verfügbar. */
        BACK_IN_STOCK("wieder verfügbar"),
        /** War verfügbar, ist jetzt nicht verfügbar. */
        OUT_OF_STOCK("nicht mehr verfügbar"),
        PRICE_CHANGED("Preis geändert"),
        DELIVERY_CHANGED("Lieferzeit geändert"),
        /** Die Abfrage schlägt neu fehl; über die Verfügbarkeit ist nichts bekannt. */
        CHECK_FAILED("Abfrage fehlgeschlagen");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
