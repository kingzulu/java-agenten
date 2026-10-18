package com.zuluindustries.agenten.core;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.model.ModelProfile;
import com.zuluindustries.agenten.model.ModelProfiles;
import com.zuluindustries.agenten.model.ProviderException;
import com.zuluindustries.agenten.model.ProviderRegistry;
import com.zuluindustries.agenten.support.FakeProvider;
import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.support.TestTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunnerTest {

    @TempDir
    Path root;

    FakeProvider primary;
    FakeProvider secondary;
    AgentRunner runner;

    @BeforeEach
    void setUp() {
        TestTools tools = new TestTools();
        AgentRuntime runtime = new AgentRuntime(tools.registry, TestTools.skills(),
                new AgentPolicy(tools.registry, root, "Europe/Berlin", Fixtures.FIXED_CLOCK));
        primary = new FakeProvider("eins");
        secondary = new FakeProvider("zwei");
        ModelProfiles profiles = new ModelProfiles(Map.of(
                "erstes", new ModelProfile("eins", "modell-1"),
                "zweites", new ModelProfile("zwei", "modell-2"),
                "ohne-provider", new ModelProfile("fehlt", "modell-3")));
        runner = new AgentRunner(runtime, profiles, new ProviderRegistry().register(primary).register(secondary));
    }

    @Test
    void usesConfiguredProfile() {
        primary.then(FakeProvider.text("ok"));

        AgentResult result = runner.run(Fixtures.agent("model: {profile: erstes}\n"), "x", 5);

        assertEquals("ok", result.text());
        assertEquals("eins", result.provider());
        assertEquals("modell-1", result.model());
        assertEquals("erstes", result.modelProfile());
    }

    @Test
    void fallsBackToSecondProfile() {
        primary.thenFail(new ProviderException("down")).thenFail(new ProviderException("down"))
                .thenFail(new ProviderException("down"));
        secondary.then(FakeProvider.text("Ersatz"));

        AgentResult result = runner.run(Fixtures.agent("model: {profile: erstes, fallback_profile: zweites}\n"), "x", 5);

        assertEquals("Ersatz", result.text());
        assertEquals("zweites", result.modelProfile());
    }

    @Test
    void returnsLastErrorWhenAllProfilesFail() {
        AgentResult unknown = runner.run(Fixtures.agent("model: {profile: gibtsnicht}\n"), "x", 5);
        assertEquals("Unbekanntes Modellprofil 'gibtsnicht'.", unknown.error());

        AgentResult missingProvider = runner.run(Fixtures.agent("model: {profile: ohne-provider}\n"), "x", 5);
        assertEquals("Provider 'fehlt' ist nicht verfügbar.", missingProvider.error());
        assertEquals("ohne-provider", missingProvider.modelProfile());

        AgentDefinition strict = Fixtures.agent("model: {profile: gibtsnicht}\nexecution: {stop_on_error: true}\n");
        assertThrows(IllegalArgumentException.class, () -> runner.run(strict, "x", 5));
    }

    @Test
    void rejectsAgentWideMemoryAndUnknownTools() {
        AgentDefinition memory = Fixtures.agent("context: {memory: {enabled: true, scope: agent}}\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runner.run(memory, "x", 5))
                .getMessage().contains("agentenweiter Speicher"));

        AgentDefinition unknownTool = Fixtures.agent("model: {profile: erstes}\ntools: {allowed: [gibtsnicht]}\n");
        assertThrows(IllegalArgumentException.class, () -> runner.run(unknownTool, "x", 5));
    }
}
