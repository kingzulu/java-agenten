package com.zuluindustries.agenten.agent;

import java.util.Set;

/** Gedächtnis eines Agenten: pro Anfrage, pro Sitzung oder agentenweit. */
public record MemoryConfig(Boolean enabled, String scope) {

    public static final String REQUEST = "request";
    public static final String SESSION = "session";
    public static final String AGENT = "agent";

    public MemoryConfig {
        enabled = enabled != null && enabled;
        scope = Validation.oneOf("context.memory.scope", scope, SESSION, Set.of(REQUEST, SESSION, AGENT));
    }

    public static MemoryConfig defaults() {
        return new MemoryConfig(null, null);
    }
}
