package com.zuluindustries.agenten.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Listenpreise je Million Tokens, geladen aus {@code config/models.yaml}.
 * Ein Modell kann mehrere Preisperioden haben, etwa wenn ein Anbieter eine
 * Preisänderung angekündigt hat; maßgeblich ist die Periode am Abrechnungstag.
 */
public final class ModelPricing {

    static final Set<String> CURRENCIES = Set.of("USD", "EUR");

    /**
     * Preise je Million Tokens.
     *
     * @param cachedInput Preis für Cache-Treffer; ohne Angabe gilt der normale Inputpreis
     * @param cacheWrite  Preis für Cache-Schreibvorgänge; ohne Angabe gilt der normale Inputpreis
     */
    public record Rates(double input, Double cachedInput, Double cacheWrite, double output) {

        public Rates {
            requireNonNegative("input", input);
            requireNonNegative("output", output);
            if (cachedInput != null) {
                requireNonNegative("cached_input", cachedInput);
            }
            if (cacheWrite != null) {
                requireNonNegative("cache_write", cacheWrite);
            }
        }

        public Rates(double input, double output) {
            this(input, null, null, output);
        }

        public double effectiveCachedInput() {
            return cachedInput != null ? cachedInput : input;
        }

        public double effectiveCacheWrite() {
            return cacheWrite != null ? cacheWrite : input;
        }

        private static void requireNonNegative(String field, double value) {
            if (!(value >= 0) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("Ungültiger Preis für '" + field + "': " + value);
            }
        }
    }

    /** Höhere Preise für Aufrufe, deren Input die Schwelle überschreitet. */
    public record LongContext(long aboveInputTokens, Rates rates) {

        public LongContext {
            if (aboveInputTokens <= 0) {
                throw new IllegalArgumentException("long_context.above_input_tokens muss positiv sein.");
            }
            Objects.requireNonNull(rates, "long_context braucht Preise.");
        }
    }

    /** Preise ab einem Stichtag; {@code validFrom == null} gilt seit jeher. */
    public record Period(LocalDate validFrom, Rates rates, LongContext longContext) {

        public Period {
            Objects.requireNonNull(rates, "Preisperiode braucht Preise.");
        }

        public Rates ratesFor(long inputTokens) {
            return longContext != null && inputTokens > longContext.aboveInputTokens() ? longContext.rates() : rates;
        }
    }

    /** Preise eines Modells in einer Währung. */
    public record ModelPrice(String currency, String source, List<Period> periods) {

        public ModelPrice {
            if (!CURRENCIES.contains(currency)) {
                throw new IllegalArgumentException("Nicht unterstützte Kostenwährung '" + currency + "'.");
            }
            if (periods == null || periods.isEmpty()) {
                throw new IllegalArgumentException("Mindestens eine Preisperiode ist erforderlich.");
            }
            List<Period> sorted = new ArrayList<>(periods);
            sorted.sort(Comparator.comparing(Period::validFrom, Comparator.nullsFirst(Comparator.naturalOrder())));
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).validFrom() == null || sorted.get(i).validFrom().equals(sorted.get(i - 1).validFrom())) {
                    throw new IllegalArgumentException("Preisperioden brauchen eindeutige valid_from-Daten.");
                }
            }
            periods = List.copyOf(sorted);
        }

        public static ModelPrice of(String currency, Rates rates) {
            return new ModelPrice(currency, null, List.of(new Period(null, rates, null)));
        }

        /** Die am Stichtag gültige Periode. */
        public Period periodAt(LocalDate date) {
            Period match = null;
            for (Period period : periods) {
                if (period.validFrom() == null || !period.validFrom().isAfter(date)) {
                    match = period;
                }
            }
            if (match == null) {
                throw new IllegalArgumentException("Für den " + date + " ist noch kein Preis gültig.");
            }
            return match;
        }
    }

    private final Map<String, ModelPrice> prices;

    public ModelPricing(Map<String, ModelPrice> prices) {
        this.prices = Collections.unmodifiableMap(new LinkedHashMap<>(prices));
    }

    public boolean contains(String model) {
        return prices.containsKey(model);
    }

    public ModelPrice get(String model) {
        ModelPrice price = prices.get(model);
        if (price == null) {
            throw new IllegalArgumentException("Keine Preise für Modell '" + model + "' hinterlegt.");
        }
        return price;
    }

    public Map<String, ModelPrice> all() {
        return prices;
    }
}
