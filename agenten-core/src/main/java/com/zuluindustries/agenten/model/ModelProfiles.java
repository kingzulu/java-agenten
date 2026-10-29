package com.zuluindustries.agenten.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Verfügbare Modellprofile aus {@code config/models.yaml}; entspricht {@code models/model_profiles.py}. */
public final class ModelProfiles {

    private final Map<String, ModelProfile> profiles;

    public ModelProfiles(Map<String, ModelProfile> profiles) {
        this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
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
