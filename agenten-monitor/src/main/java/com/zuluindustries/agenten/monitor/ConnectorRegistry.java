package com.zuluindustries.agenten.monitor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Registrierte Zugriffsarten; YAML wählt nur aus, was im Code registriert ist. */
public final class ConnectorRegistry {

    private final Map<String, PortalConnector.Factory> factories = new LinkedHashMap<>();

    /** Registry mit den mitgelieferten Connectoren. */
    public static ConnectorRegistry defaults() {
        return new ConnectorRegistry().register(DemoConnector.TYPE, DemoConnector::new);
    }

    public ConnectorRegistry register(String type, PortalConnector.Factory factory) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Ein Connector-Typ darf nicht leer sein.");
        }
        if (factories.putIfAbsent(type, factory) != null) {
            throw new IllegalArgumentException("Connector-Typ bereits registriert: " + type);
        }
        return this;
    }

    public boolean contains(String type) {
        return factories.containsKey(type);
    }

    public PortalConnector.Factory get(String type) {
        PortalConnector.Factory factory = factories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("Unbekannter Connector-Typ '" + type + "'. Verfügbar: "
                    + String.join(", ", factories.keySet()));
        }
        return factory;
    }

    public Set<String> types() {
        return factories.keySet();
    }
}
