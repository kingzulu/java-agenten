package com.zuluindustries.agenten.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Registrierte Provideradapter; entspricht {@code models/registry.py}. */
public final class ProviderRegistry {

    private final Map<String, ModelProvider> providers = new LinkedHashMap<>();

    public ProviderRegistry register(ModelProvider provider) {
        if (providers.containsKey(provider.name())) {
            throw new IllegalArgumentException("Provider bereits registriert: " + provider.name());
        }
        providers.put(provider.name(), provider);
        return this;
    }

    public ModelProvider get(String name) {
        ModelProvider provider = providers.get(name);
        if (provider == null) {
            throw new IllegalArgumentException("Provider '" + name + "' ist nicht verfügbar.");
        }
        return provider;
    }

    public Set<String> names() {
        return providers.keySet();
    }
}
