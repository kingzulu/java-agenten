package com.zuluindustries.agenten.agent;

import java.util.List;

/**
 * Providerunabhängige Agentendefinition, wie sie in {@code agents/definitions/*.yaml} steht.
 *
 * <p>Entspricht {@code agents/definition.py}. Die Instructions stehen in {@code description},
 * die technische ID ist der Dateiname, {@code name} der Anzeigename.</p>
 */
public record AgentDefinition(
        String id,
        String name,
        Integer version,
        Boolean enabled,
        String description,
        ModelConfig model,
        List<String> capabilities,
        List<String> skills,
        ToolConfig tools,
        ExecutionConfig execution,
        PermissionConfig permissions,
        GuardrailConfig guardrails,
        ContextConfig context,
        OutputConfig output) {

    public static final String DEFAULT_DESCRIPTION = "Du bist ein hilfreicher Assistent.";

    public AgentDefinition {
        version = Validation.atLeast("version", version, 1, 1);
        enabled = enabled == null || enabled;
        description = description == null ? DEFAULT_DESCRIPTION : description;
        model = model == null ? ModelConfig.defaults() : model;
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        skills = skills == null ? List.of() : List.copyOf(skills);
        tools = tools == null ? ToolConfig.defaults() : tools;
        execution = execution == null ? ExecutionConfig.defaults() : execution;
        permissions = permissions == null ? PermissionConfig.defaults() : permissions;
        guardrails = guardrails == null ? GuardrailConfig.defaults() : guardrails;
        context = context == null ? ContextConfig.defaults() : context;
        output = output == null ? OutputConfig.defaults() : output;
    }

    /** Standardagent ohne Tools und Skills, wenn kein Agent angegeben wurde. */
    public static AgentDefinition defaultAssistant(String modelProfile) {
        return new AgentDefinition(null, null, null, null, null,
                new ModelConfig(modelProfile, null, null, null, null),
                null, null, null, null, null, null, null, null);
    }

    /** Die Agenteninstruktionen (YAML-Feld {@code description}). */
    public String instructions() {
        return description;
    }

    public AgentDefinition withIdentity(String newId, String newName) {
        return new AgentDefinition(newId, newName, version, enabled, description, model, capabilities, skills,
                tools, execution, permissions, guardrails, context, output);
    }
}
