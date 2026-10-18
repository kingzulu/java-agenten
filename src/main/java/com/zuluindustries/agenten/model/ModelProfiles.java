package com.zuluindustries.agenten.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Verfügbare Modellprofile; entspricht {@code models/model_profiles.py}. */
public final class ModelProfiles {

    private final Map<String, ModelProfile> profiles;

    public ModelProfiles(Map<String, ModelProfile> profiles) {
        this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    public static ModelProfiles defaults() {
        Map<String, ModelProfile> profiles = new LinkedHashMap<>();
        profiles.put("scaleway-mistral-small",
                new ModelProfile("scaleway", "mistral-small-3.2-24b-instruct-2506", "EUR"));
        profiles.put("openai-mini", new ModelProfile("openai", "gpt-5.4-mini"));
        profiles.put("claude-sonnet", new ModelProfile("anthropic", "claude-sonnet-4-5"));
        profiles.put("claude-haiku", new ModelProfile("anthropic", "claude-haiku-4-5"));
        profiles.put("gemini-flash", new ModelProfile("gemini", "gemini-3.8-flash"));
        return new ModelProfiles(profiles);
    }

    public boolean contains(String name) {
        return profiles.containsKey(name);
    }

    public ModelProfile get(String name) {
        ModelProfile profile = profiles.get(name);
        if (profile == null) {
            throw new IllegalArgumentException("Unbekanntes Modellprofil '" + name + "'. Erlaubt sind: "
                    + String.join(", ", new TreeSet<>(profiles.keySet())) + ".");
        }
        return profile;
    }

    public Map<String, ModelProfile> all() {
        return profiles;
    }
}
