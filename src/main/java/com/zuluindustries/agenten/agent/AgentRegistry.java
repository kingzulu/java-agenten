package com.zuluindustries.agenten.agent;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Registrierte, aktivierte Agenten; entspricht {@code agents/registry.py} und {@code core/agent_config.py}.
 * Zur Laufzeit kommen nur über {@link #register} weitere Agenten hinzu; vorhandene werden nie ersetzt.
 */
public final class AgentRegistry {

    private final Map<String, AgentDefinition> agents;

    public AgentRegistry(Map<String, AgentDefinition> agents) {
        this.agents = Collections.synchronizedMap(new LinkedHashMap<>(agents));
    }

    public static AgentRegistry fromDirectory(Path directory) {
        return new AgentRegistry(AgentDefinitionLoader.loadAll(directory));
    }

    /**
     * Liefert die Konfiguration eines Agenten. Ohne Agentennamen wird ein
     * Standardassistent mit dem Standardmodellprofil zurückgegeben.
     */
    public AgentDefinition get(String agentName, String defaultModelProfile) {
        if (agentName == null) {
            return AgentDefinition.defaultAssistant(defaultModelProfile);
        }
        AgentDefinition definition = agents.get(agentName);
        if (definition == null) {
            throw new IllegalArgumentException("Unbekannter Agent '" + agentName + "'.");
        }
        return definition;
    }

    /** Registriert einen neuen Agenten prozesslokal; eine vorhandene ID wird nicht überschrieben. */
    public void register(AgentDefinition definition) {
        if (agents.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalArgumentException("Agent-ID existiert bereits.");
        }
    }

    public boolean contains(String agentName) {
        return agents.containsKey(agentName);
    }

    public Set<String> names() {
        synchronized (agents) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(agents.keySet()));
        }
    }

    /** Momentaufnahme aller Agenten in Registrierungsreihenfolge. */
    public Map<String, AgentDefinition> all() {
        synchronized (agents) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(agents));
        }
    }
}
