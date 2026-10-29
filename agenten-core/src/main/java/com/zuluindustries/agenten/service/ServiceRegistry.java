package com.zuluindustries.agenten.service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explizit registrierte Services. Fremder Code wird nicht automatisch entdeckt.
 * Berechtigungsnamen im Manifest ersetzen keine Laufzeit-Zugriffsprüfung.
 */
public final class ServiceRegistry {

    private final Map<String, ServiceManifest> services = new LinkedHashMap<>();

    public ServiceRegistry register(ServiceManifest manifest) {
        if (services.containsKey(manifest.key())) {
            throw new IllegalArgumentException("Service bereits registriert: " + manifest.key());
        }
        services.put(manifest.key(), manifest);
        return this;
    }

    public ServiceManifest get(String key) {
        ServiceManifest manifest = services.get(key);
        if (manifest == null) {
            throw new IllegalArgumentException("Service nicht registriert: " + key);
        }
        return manifest;
    }

    public Collection<ServiceManifest> list() {
        return Collections.unmodifiableCollection(services.values());
    }
}
