package com.zuluindustries.agenten.tool;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Einfaches Tool aus Spezifikation und Funktion. */
public final class FunctionTool implements Tool {

    private final ToolSpec spec;
    private final Set<ToolCategory> categories;
    private final Function<Map<String, Object>, Object> function;

    public FunctionTool(ToolSpec spec, Set<ToolCategory> categories,
                        Function<Map<String, Object>, Object> function) {
        this.spec = spec;
        this.categories = categories.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(categories));
        this.function = function;
    }

    public static FunctionTool of(String name, String description, Map<String, Object> parameters,
                                  Function<Map<String, Object>, Object> function, ToolCategory... categories) {
        return new FunctionTool(new ToolSpec(name, description, parameters), Set.of(categories), function);
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public Set<ToolCategory> categories() {
        return categories;
    }

    @Override
    public Object execute(Map<String, Object> arguments) {
        return function.apply(arguments);
    }
}
