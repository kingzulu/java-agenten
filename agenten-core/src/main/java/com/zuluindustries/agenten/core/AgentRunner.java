package com.zuluindustries.agenten.core;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.agent.MemoryConfig;
import com.zuluindustries.agenten.model.ModelProfile;
import com.zuluindustries.agenten.model.ModelProfiles;
import com.zuluindustries.agenten.model.ModelProvider;
import com.zuluindustries.agenten.model.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Führt einen konfigurierten Agenten aus, inklusive Fallback-Modellprofil;
 * entspricht {@code core/agent_runner.py}.
 */
public final class AgentRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AgentRunner.class);

    private final AgentRuntime runtime;
    private final ModelProfiles profiles;
    private final ProviderRegistry providers;

    public AgentRunner(AgentRuntime runtime, ModelProfiles profiles, ProviderRegistry providers) {
        this.runtime = runtime;
        this.profiles = profiles;
        this.providers = providers;
    }

    public AgentRuntime runtime() {
        return runtime;
    }

    public AgentResult run(AgentDefinition agent, String userInput, int defaultMaxSteps) {
        return run(agent, userInput, defaultMaxSteps, null);
    }

    public AgentResult run(AgentDefinition agent, String userInput, int defaultMaxSteps, Conversation conversation) {
        MemoryConfig memory = agent.context().memory();
        if (memory.enabled() && MemoryConfig.AGENT.equals(memory.scope())) {
            throw new IllegalArgumentException("Persistenter agentenweiter Speicher ist noch nicht eingerichtet.");
        }

        // Unbekannte Tools oder Skills sind Definitionsfehler und lösen keinen Fallback aus.
        runtime.capabilities().prepare(runtime.policy().effectiveAllowedTools(agent), agent.skills());

        List<String> profileNames = new ArrayList<>();
        profileNames.add(agent.model().profile());
        String fallback = agent.model().fallbackProfile();
        if (fallback != null && !profileNames.contains(fallback)) {
            profileNames.add(fallback);
        }

        RuntimeException lastError = null;
        AgentResult lastResult = null;
        List<ModelCall> spent = List.of();
        String lastProvider = null;
        String lastModel = null;
        String lastProfile = null;

        for (String profileName : profileNames) {
            if (!profiles.contains(profileName)) {
                lastError = new IllegalArgumentException("Unbekanntes Modellprofil '" + profileName + "'.");
                continue;
            }
            ModelProfile profile = profiles.get(profileName);
            lastProvider = profile.provider();
            lastModel = profile.model();
            lastProfile = profileName;

            LOG.info("Running agent name={} version={} profile={} provider={}",
                    agent.name(), agent.version(), profileName, profile.provider());

            ModelProvider provider;
            try {
                provider = providers.get(profile.provider());
            } catch (IllegalArgumentException error) {
                lastError = error;
                continue;
            }

            try {
                AgentResult result = runtime.runTurn(provider, profile.model(), agent, userInput, conversation,
                                defaultMaxSteps)
                        .withModelInfo(profile.provider(), profile.model(), profileName);
                if (!result.failed()) {
                    return result.withEarlierCalls(spent);
                }
                lastResult = result.withEarlierCalls(spent);
                spent = lastResult.modelCalls();
                lastError = new AgentException(result.error());
            } catch (RuntimeException error) {
                lastError = error;
            }

            if (!profileName.equals(profileNames.getLast())) {
                LOG.warn("Model profile failed; using fallback profile={} error={}",
                        profileNames.getLast(), AgentRuntime.messageOf(lastError));
            }
        }

        if (agent.execution().stopOnError()) {
            throw lastError;
        }
        if (lastResult != null) {
            return lastResult;
        }
        String message = AgentRuntime.messageOf(lastError);
        return new AgentResult(null, "Agentenfehler: " + message, null, 0, 0, 0, message,
                lastProvider, lastModel, lastProfile);
    }
}
