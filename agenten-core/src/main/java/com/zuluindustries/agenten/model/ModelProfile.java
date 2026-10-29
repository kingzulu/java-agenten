package com.zuluindustries.agenten.model;

/** Ein benanntes Modellprofil: Provider und Modell. Preis und Währung stehen in {@link ModelPricing}. */
public record ModelProfile(String provider, String model) {
}
