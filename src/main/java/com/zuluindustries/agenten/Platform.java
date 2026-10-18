package com.zuluindustries.agenten;

import com.zuluindustries.agenten.agent.AgentRegistry;
import com.zuluindustries.agenten.core.AgentPolicy;
import com.zuluindustries.agenten.core.AgentResult;
import com.zuluindustries.agenten.core.AgentRunner;
import com.zuluindustries.agenten.core.AgentRuntime;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.model.CostCalculator;
import com.zuluindustries.agenten.model.HttpTransport;
import com.zuluindustries.agenten.model.ModelCatalog;
import com.zuluindustries.agenten.model.ModelProfiles;
import com.zuluindustries.agenten.model.ProviderRegistry;
import com.zuluindustries.agenten.model.anthropic.AnthropicProvider;
import com.zuluindustries.agenten.model.openai.OpenAiProvider;
import com.zuluindustries.agenten.model.scaleway.ScalewayProvider;
import com.zuluindustries.agenten.service.ServiceRegistry;
import com.zuluindustries.agenten.skill.SkillRegistry;
import com.zuluindustries.agenten.tool.ToolRegistry;
import com.zuluindustries.agenten.tool.tasks.TaskTools;
import com.zuluindustries.agenten.workflow.AgentStepExecutor;
import com.zuluindustries.agenten.workflow.InMemoryWorkflowRunRepository;
import com.zuluindustries.agenten.workflow.WorkflowActionRegistry;
import com.zuluindustries.agenten.workflow.WorkflowEngine;
import com.zuluindustries.agenten.workflow.WorkflowLoader;
import com.zuluindustries.agenten.workflow.WorkflowRunRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Function;

/**
 * Zusammensetzung der Plattform (Composition Root): lädt Definitionen aus dem
 * Projektverzeichnis und verdrahtet Registries, Provider, Runtime und Workflow-Engine.
 */
public final class Platform {

    private final Settings settings;
    private final ToolRegistry tools;
    private final SkillRegistry skills;
    private final AgentRegistry agents;
    private final ModelCatalog catalog;
    private final ModelProfiles profiles;
    private final CostCalculator costs;
    private final AgentRunner runner;
    private final WorkflowActionRegistry actions;
    private final WorkflowEngine workflows;
    private final ServiceRegistry services;

    private Platform(Builder builder) {
        this.settings = builder.settings;
        Path root = settings.projectRoot();
        this.tools = builder.tools;
        this.skills = SkillRegistry.fromDirectory(root.resolve("skills"));
        this.agents = AgentRegistry.fromDirectory(root.resolve("agents").resolve("definitions"));
        this.catalog = builder.catalog != null ? builder.catalog : ModelCatalog.load(root);
        this.profiles = catalog.profiles();
        this.costs = new CostCalculator(catalog.pricing(), builder.clock);
        AgentPolicy policy = new AgentPolicy(tools, root, settings.agentTimezone(), builder.clock);
        AgentRuntime runtime = new AgentRuntime(tools, skills, policy);
        this.runner = new AgentRunner(runtime, profiles, builder.providers);
        this.actions = builder.actions;
        this.services = builder.services;
        AgentStepExecutor executor = new AgentStepExecutor() {
            @Override
            public boolean knows(String agentName) {
                return agents.contains(agentName);
            }

            @Override
            public AgentResult run(String agentName, String prompt) {
                return runner.run(agents.get(agentName, settings.defaultModelProfile()), prompt,
                        settings.maxAgentSteps());
            }
        };
        this.workflows = new WorkflowEngine(builder.repository,
                WorkflowLoader.loadAll(root.resolve("workflows").resolve("definitions")),
                actions, executor, costs, builder.clock);
    }

    public static Builder builder(Settings settings, Function<String, String> environment) {
        return new Builder(settings, environment);
    }

    public Settings settings() {
        return settings;
    }

    public ToolRegistry tools() {
        return tools;
    }

    public SkillRegistry skills() {
        return skills;
    }

    public AgentRegistry agents() {
        return agents;
    }

    public ModelCatalog catalog() {
        return catalog;
    }

    public ModelProfiles profiles() {
        return profiles;
    }

    public CostCalculator costs() {
        return costs;
    }

    public AgentRunner runner() {
        return runner;
    }

    public WorkflowActionRegistry actions() {
        return actions;
    }

    public WorkflowEngine workflows() {
        return workflows;
    }

    public ServiceRegistry services() {
        return services;
    }

    /** Konfigurierbarer Aufbau; Standardwerte entsprechen dem Python-Projekt. */
    public static final class Builder {

        private final Settings settings;
        private ToolRegistry tools;
        private ProviderRegistry providers;
        private ModelCatalog catalog;
        private WorkflowActionRegistry actions = new WorkflowActionRegistry();
        private WorkflowRunRepository repository = new InMemoryWorkflowRunRepository();
        private ServiceRegistry services = new ServiceRegistry();
        private Clock clock = Clock.systemUTC();

        private Builder(Settings settings, Function<String, String> environment) {
            this.settings = settings;
            this.tools = new ToolRegistry().registerAll(new TaskTools().tools());
            HttpTransport transport = HttpTransport.jdk();
            this.providers = new ProviderRegistry()
                    .register(new OpenAiProvider(transport, environment))
                    .register(new AnthropicProvider(transport, environment))
                    .register(new ScalewayProvider(transport, environment));
        }

        public Builder tools(ToolRegistry value) {
            this.tools = value;
            return this;
        }

        public Builder providers(ProviderRegistry value) {
            this.providers = value;
            return this;
        }

        /** Modellprofile und Preise; ohne Angabe aus {@code config/models.yaml} geladen. */
        public Builder catalog(ModelCatalog value) {
            this.catalog = value;
            return this;
        }

        public Builder actions(WorkflowActionRegistry value) {
            this.actions = value;
            return this;
        }

        public Builder repository(WorkflowRunRepository value) {
            this.repository = value;
            return this;
        }

        public Builder services(ServiceRegistry value) {
            this.services = value;
            return this;
        }

        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        public Platform build() {
            return new Platform(this);
        }
    }
}
