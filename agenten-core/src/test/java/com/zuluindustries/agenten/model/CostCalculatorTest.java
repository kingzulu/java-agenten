package com.zuluindustries.agenten.model;

import com.zuluindustries.agenten.core.ModelCall;
import com.zuluindustries.agenten.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CostCalculatorTest {

    final CostCalculator calculator = new CostCalculator(Fixtures.pricing(), Fixtures.FIXED_CLOCK);

    private static ModelCall call(String model, long in, long out, long cached, long cacheWrite) {
        return new ModelCall(0, 0, "initial", "p", model, in, out, in + out, cached, cacheWrite);
    }

    @Test
    void calculatesListPriceCosts() {
        CostCalculator.Costs costs = calculator.calculate("gpt-5.4-mini", TokenUsage.of(1_000_000, 100_000));

        assertEquals(0.75, costs.inputCost(), 1e-12);
        assertEquals(0.45, costs.outputCost(), 1e-12);
        assertEquals(1_200_000, costs.microUsd());
        assertEquals(0, costs.microEur());
        assertEquals("EUR", calculator.calculate("mistral-small-3.2-24b-instruct-2506", TokenUsage.of(1, 1)).currency());
    }

    @Test
    void pricesCacheReadsAndWritesSeparately() {
        // 1 Mio. Input, davon 600.000 aus dem Cache und 100.000 neu in den Cache geschrieben.
        CostCalculator.Costs costs = calculator.calculate("claude-haiku-4-5",
                new TokenUsage(1_000_000, 0, 1_000_000, 600_000, 100_000));

        assertEquals(0.30 * 1.00 + 0.60 * 0.10 + 0.10 * 1.25, costs.inputCost(), 1e-12);
        assertEquals(600_000, costs.cachedInputTokens());

        // Ohne cache_write-Preis gilt der normale Inputpreis.
        assertEquals(0.75, calculator.calculate("gpt-5.4-mini",
                new TokenUsage(1_000_000, 0, 1_000_000, 0, 1_000_000)).inputCost(), 1e-12);
    }

    @Test
    void appliesLongContextRatesPerCall() {
        ModelPricing pricing = new ModelPricing(Map.of("gross", new ModelPricing.ModelPrice("USD", null, List.of(
                new ModelPricing.Period(null, new ModelPricing.Rates(1.0, 2.0),
                        new ModelPricing.LongContext(100_000, new ModelPricing.Rates(5.0, 10.0)))))));
        CostCalculator longContext = new CostCalculator(pricing, Fixtures.FIXED_CLOCK);

        assertEquals(0.1, longContext.calculate("gross", TokenUsage.of(100_000, 0)).totalCost(), 1e-12);
        assertEquals(100_001 * 5.0 / 1_000_000, longContext.calculate("gross", TokenUsage.of(100_001, 0)).totalCost(), 1e-12);
        // Zwei kleine Aufrufe bleiben unter der Schwelle, auch wenn ihre Summe darüber liegt.
        assertEquals(0.16, CostCalculator.sum(longContext.calculate(List.of(
                call("gross", 80_000, 0, 0, 0), call("gross", 80_000, 0, 0, 0)))).totalCost(), 1e-12);
    }

    @Test
    void usesThePricePeriodOfTheBillingDay() {
        ModelPricing pricing = new ModelPricing(Map.of("m", new ModelPricing.ModelPrice("USD", null, List.of(
                new ModelPricing.Period(LocalDate.parse("2027-01-01"), new ModelPricing.Rates(2.0, 2.0), null),
                new ModelPricing.Period(null, new ModelPricing.Rates(1.0, 1.0), null)))));
        CostCalculator periods = new CostCalculator(pricing, Fixtures.FIXED_CLOCK);
        TokenUsage million = TokenUsage.of(1_000_000, 0);

        assertEquals(1.0, periods.calculate("m", million).totalCost(), 1e-12);
        assertEquals(1.0, periods.calculate("m", million, LocalDate.parse("2026-12-31")).totalCost(), 1e-12);
        assertEquals(2.0, periods.calculate("m", million, LocalDate.parse("2027-01-01")).totalCost(), 1e-12);
    }

    @Test
    void sumsEveryCallWithItsOwnModelAndCurrency() {
        List<CostCalculator.Costs> costs = calculator.calculate(List.of(
                call("gpt-5.4-mini", 1_000_000, 0, 0, 0),
                call("claude-haiku-4-5", 1_000_000, 0, 0, 0),
                call("mistral-small-3.2-24b-instruct-2506", 1_000_000, 0, 0, 0)));

        assertEquals(2, costs.size());
        assertEquals(1.75, costs.get(0).totalCost(), 1e-12);
        assertEquals("USD", costs.get(0).currency());
        assertEquals(0.15, costs.get(1).totalCost(), 1e-12);
        assertEquals("EUR", costs.get(1).currency());
    }

    @Test
    void refusesUnknownModelsAndMixedCurrencies() {
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate("unbekannt", TokenUsage.of(1, 1)));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(List.of(call("unbekannt", 1, 1, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> CostCalculator.sum(List.of(
                calculator.calculate("gpt-5.4-mini", TokenUsage.of(1, 1)),
                calculator.calculate("mistral-small-3.2-24b-instruct-2506", TokenUsage.of(1, 1)))));
        assertEquals(4, CostCalculator.sum(List.of(
                calculator.calculate("gpt-5.4-mini", TokenUsage.of(1, 1)),
                calculator.calculate("claude-haiku-4-5", TokenUsage.of(1, 1)))).totalTokens());
    }
}
