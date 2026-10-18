package com.zuluindustries.agenten.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Explizit registrierte Tools; entspricht {@code tools/registry.py}.
 * Es werden keine Tools automatisch entdeckt.
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        if (tools.containsKey(tool.name())) {
            throw new IllegalArgumentException("Tool bereits registriert: " + tool.name());
        }
        tools.put(tool.name(), tool);
        return this;
    }

    public ToolRegistry registerAll(Collection<? extends Tool> newTools) {
        newTools.forEach(this::register);
        return this;
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public boolean has(String name, ToolCategory category) {
        Tool tool = tools.get(name);
        return tool != null && tool.categories().contains(category);
    }

    public Collection<Tool> all() {
        return Collections.unmodifiableCollection(tools.values());
    }

    /** Spezifikationen der genannten Tools in Registrierungsreihenfolge. */
    public List<ToolSpec> specsFor(Collection<String> names) {
        return tools.values().stream()
                .filter(tool -> names.contains(tool.name()))
                .map(Tool::spec)
                .toList();
    }
}
