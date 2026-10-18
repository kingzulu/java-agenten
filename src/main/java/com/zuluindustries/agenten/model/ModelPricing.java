package com.zuluindustries.agenten.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Listenpreise je Million Tokens; entspricht {@code models/model_pricing.py}. */
public final class ModelPricing {

    /** Preis je Million Input- und Output-Tokens. */
    public record Price(double input, double output, String currency) {

        public Price {
            currency = currency == null ? "USD" : currency;
        }

        public Price(double input, double output) {
            this(input, output, null);
        }
    }

    private final Map<String, Price> prices;

    public ModelPricing(Map<String, Price> prices) {
        this.prices = Collections.unmodifiableMap(new LinkedHashMap<>(prices));
    }

    public static ModelPricing defaults() {
        Map<String, Price> prices = new LinkedHashMap<>();
        prices.put("gpt-5.4-mini", new Price(0.75, 4.50));
        prices.put("claude-sonnet-4-5", new Price(3.00, 15.00));
        prices.put("claude-haiku-4-5", new Price(1.00, 5.00));
        prices.put("gemini-3.8-flash", new Price(0.75, 3.75));
        prices.put("mistral-small-3.2-24b-instruct-2506", new Price(0.15, 0.35, "EUR"));
        return new ModelPricing(prices);
    }

    public Price get(String model) {
        Price price = prices.get(model);
        if (price == null) {
            throw new IllegalArgumentException("Keine Preise für Modell '" + model + "' hinterlegt.");
        }
        return price;
    }
}
