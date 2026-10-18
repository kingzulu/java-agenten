package com.zuluindustries.agenten.model;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Lokale Kostenberechnung aus Tokenverbrauch; entspricht {@code cost_calculator.py}.
 * Die Werte sind Listenpreisberechnungen, keine bestätigten Rechnungsbeträge.
 */
public final class CostCalculator {

    /** Berechnete Kosten eines oder mehrerer Modellaufrufe in einer Währung. */
    public record Costs(long inputTokens, long outputTokens, long totalTokens,
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
    }

    private final ModelPricing pricing;

    public CostCalculator(ModelPricing pricing) {
        this.pricing = pricing;
    }

    public Costs calculate(String model, long inputTokens, long outputTokens) {
        ModelPricing.Price price = pricing.get(model);
        if (!Set.of("USD", "EUR").contains(price.currency())) {
            throw new IllegalArgumentException("Nicht unterstützte Kostenwährung '" + price.currency() + "'.");
        }
        double inputCost = inputTokens / 1_000_000.0 * price.input();
        double outputCost = outputTokens / 1_000_000.0 * price.output();
        return new Costs(inputTokens, outputTokens, inputTokens + outputTokens,
                inputCost, outputCost, inputCost + outputCost, price.currency());
    }

    /** Summiert Kosten; verschiedene Währungen werden nicht addiert. */
    public static Costs sum(Collection<Costs> costs) {
        Set<String> currencies = new HashSet<>();
        long in = 0;
        long out = 0;
        long total = 0;
        double inCost = 0;
        double outCost = 0;
        double totalCost = 0;
        for (Costs item : costs) {
            currencies.add(item.currency());
            in += item.inputTokens();
            out += item.outputTokens();
            total += item.totalTokens();
            inCost += item.inputCost();
            outCost += item.outputCost();
            totalCost += item.totalCost();
        }
        if (currencies.size() != 1) {
            throw new IllegalArgumentException("Modellkosten in mehreren Währungen können nicht summiert werden.");
        }
        return new Costs(in, out, total, inCost, outCost, totalCost, currencies.iterator().next());
    }
}
