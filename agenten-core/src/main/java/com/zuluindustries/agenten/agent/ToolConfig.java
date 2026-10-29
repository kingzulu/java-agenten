package com.zuluindustries.agenten.agent;

import java.util.List;
import java.util.Set;

/** Tool-Freigaben, Sperren und Toolstrategie. */
public record ToolConfig(List<String> allowed, List<String> denied, String strategy) {

    public static final String AUTO = "auto";
    public static final String MANUAL = "manual";

    public ToolConfig {
        allowed = allowed == null ? List.of() : List.copyOf(allowed);
        denied = denied == null ? List.of() : List.copyOf(denied);
        strategy = Validation.oneOf("tools.strategy", strategy, AUTO, Set.of(AUTO, MANUAL));
    }

    public static ToolConfig defaults() {
        return new ToolConfig(null, null, null);
    }
}
