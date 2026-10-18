package com.zuluindustries.agenten.model;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lokale Kostenberechnung aus Tokenverbrauch; entspricht {@code cost_calculator.py}.
 * Jeder Modellaufruf wird einzeln mit dem Preis seines Modells berechnet, inklusive
 * Prompt-Cache und Long-Context-Stufe. Die Werte sind Listenpreisberechnungen ohne
 * Steuern und Rabatte, keine bestätigten Rechnungsbeträge.
 */
public final class CostCalculator {

    /** Berechnete Kosten eines oder mehrerer Modellaufrufe in einer Währung. */
    public record Costs(long inputTokens, long outputTokens, long totalTokens, long cachedInputTokens,
                        double inputCost, double outputCost, double totalCost, String currency) {

        /** Betrag in Mikro-Einheiten (USD bzw. EUR), wie er im Workflow gespeichert wird. */
        public long totalMicro() {
            return Math.round(totalCost * 1_000_000);
        }

        public long microUsd() {
            return "USD".equals(currency) ? totalMicro() : 0;
        }

        public long microEur() {
            return "EUR".equals(currency) ? totalMicro() : 0;
        }

        Costs plus(Costs other) {
            if (!currency.equals(other.currency)) {
                throw new IllegalArgumentException("Modellkosten in mehreren Währungen können nicht summiert werden.");
            }
            return new Costs(inputTokens + other.inputTokens, outputTokens + other.outputTokens,
                    totalTokens + other.totalTokens, cachedInputTokens + other.cachedInputTokens,
                    inputCost + other.inputCost, outputCost + other.outputCost, totalCost + other.totalCost,
                    currency);
        }
    }

    private final ModelPricing pricing;
    private final Clock clock;

    public CostCalculator(ModelPricing pricing) {
        this(pricing, Clock.systemUTC());
    }

    public CostCalculator(ModelPricing pricing, Clock clock) {
        this.pricing = pricing;
        this.clock = clock;
    }

    /** Abrechnungstag für Preisperioden (UTC). */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public ModelPricing pricing() {
        return pricing;
    }

    /** Kosten eines einzelnen Modellaufrufs zum heutigen Preis. */
    public Costs calculate(String model, TokenUsage usage) {
        return calculate(model, usage, today());
    }

    public Costs calculate(String model, TokenUsage usage, LocalDate date) {
        ModelPricing.ModelPrice price = pricing.get(model);
        ModelPricing.Rates rates = price.periodAt(date).ratesFor(usage.inputTokens());
        double inputCost = (usage.uncachedInputTokens() * rates.input()
                + usage.cachedInputTokens() * rates.effectiveCachedInput()
                + usage.cacheWriteTokens() * rates.effectiveCacheWrite()) / 1_000_000.0;
        double outputCost = usage.outputTokens() * rates.output() / 1_000_000.0;
        return new Costs(usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), usage.cachedInputTokens(),
                inputCost, outputCost, inputCost + outputCost, price.currency());
    }

    /**
     * Kosten aller Modellaufrufe, je Währung summiert (in der Regel genau ein Eintrag).
     * Ein Aufruf ohne hinterlegten Preis führt zu einem Fehler statt zu stillen Nullkosten.
     */
    public List<Costs> calculate(Collection<? extends ModelUsage> calls) {
        Map<String, Costs> byCurrency = new LinkedHashMap<>();
        LocalDate today = today();
        for (ModelUsage call : calls) {
            Costs costs = calculate(call.model(), call.usage(), today);
            byCurrency.merge(costs.currency(), costs, Costs::plus);
        }
        return new ArrayList<>(byCurrency.values());
    }

    /** Summiert Kosten; verschiedene Währungen werden nicht addiert. */
    public static Costs sum(Collection<Costs> costs) {
        Costs total = null;
        for (Costs item : costs) {
            total = total == null ? item : total.plus(item);
        }
        if (total == null) {
            throw new IllegalArgumentException("Keine Kosten zum Summieren.");
        }
        return total;
    }
}
