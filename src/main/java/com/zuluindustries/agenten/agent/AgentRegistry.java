package com.zuluindustries.agenten.agent;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Registrierte, aktivierte Agenten; entspricht {@code agents/registry.py} und {@code core/agent_config.py}. */
public final class AgentRegistry {

    private final Map<String, AgentDefinition> agents;

    public AgentRegistry(Map<String, AgentDefinition> agents) {
        this.agents = Collections.unmodifiableMap(new LinkedHashMap<>(agents));
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

    public boolean contains(String agentName) {
        return agents.containsKey(agentName);
    }

    public Set<String> names() {
        return agents.keySet();
    }

    public Map<String, AgentDefinition> all() {
        return agents;
    }
}
