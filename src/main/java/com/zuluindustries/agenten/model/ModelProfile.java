package com.zuluindustries.agenten.model;

/** Ein benanntes Modellprofil: Provider, Modell und Abrechnungswährung. */
public record ModelProfile(String provider, String model, String currency) {

    public ModelProfile {
        currency = currency == null ? "USD" : currency;
    }

    public ModelProfile(String provider, String model) {
        this(provider, model, null);
    }
}
