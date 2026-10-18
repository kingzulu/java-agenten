package com.zuluindustries.agenten.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CostCalculatorTest {

    final CostCalculator calculator = new CostCalculator(ModelPricing.defaults());

    @Test
    void calculatesListPriceCosts() {
        CostCalculator.Costs costs = calculator.calculate("gpt-5.4-mini", 1_000_000, 100_000);

        assertEquals(0.75, costs.inputCost(), 1e-12);
        assertEquals(0.45, costs.outputCost(), 1e-12);
        assertEquals(1_200_000, costs.microUsd());
        assertEquals(0, costs.microEur());
        assertEquals("EUR", calculator.calculate("mistral-small-3.2-24b-instruct-2506", 1, 1).currency());
    }

    @Test
    void refusesUnknownModelsAndMixedCurrencies() {
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate("unbekannt", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> CostCalculator.sum(List.of(
                calculator.calculate("gpt-5.4-mini", 1, 1),
                calculator.calculate("mistral-small-3.2-24b-instruct-2506", 1, 1))));
        assertEquals(4, CostCalculator.sum(List.of(
                calculator.calculate("gpt-5.4-mini", 1, 1),
                calculator.calculate("claude-haiku-4-5", 1, 1))).totalTokens());
    }
}
