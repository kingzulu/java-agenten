package com.zuluindustries.agenten;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.agent.AgentDefinitionLoader;
import com.zuluindustries.agenten.agent.AgentRegistry;
import com.zuluindustries.agenten.builder.AgentBuilder;
import com.zuluindustries.agenten.builder.AgentDefinitionWriter;
import com.zuluindustries.agenten.coordinator.Coordinator;
import com.zuluindustries.agenten.core.AgentPolicy;
import com.zuluindustries.agenten.core.AgentResult;
import com.zuluindustries.agenten.core.AgentRunner;
import com.zuluindustries.agenten.core.AgentRuntime;
import com.zuluindustries.agenten.core.Conversation;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.extension.CliCommand;
import com.zuluindustries.agenten.extension.PlatformExtension;
import com.zuluindustries.agenten.model.CostCalculator;
import com.zuluindustries.agenten.model.HttpTransport;
import com.zuluindustries.agenten.model.ModelCatalog;
import com.zuluindustries.agenten.model.ModelProfiles;
import com.zuluindustries.agenten.model.ProviderRegistry;
import com.zuluindustries.agenten.model.anthropic.AnthropicProvider;
import com.zuluindustries.agenten.model.openai.OpenAiProvider;
import com.zuluindustries.agenten.model.scaleway.ScalewayProvider;
import com.zuluindustries.agenten.service.ServiceRegistry;
import com.zuluindustries.agenten.skill.Skill;
import com.zuluindustries.agenten.skill.SkillLoader;
import com.zuluindustries.agenten.skill.SkillRegistry;
import com.zuluindustries.agenten.tool.Tool;
import com.zuluindustries.agenten.tool.ToolRegistry;
import com.zuluindustries.agenten.workflow.AgentStepExecutor;
import com.zuluindustries.agenten.workflow.InMemoryWorkflowRunRepository;
import com.zuluindustries.agenten.workflow.WorkflowActionRegistry;
import com.zuluindustries.agenten.workflow.WorkflowDefinition;
import com.zuluindustries.agenten.workflow.WorkflowEngine;
import com.zuluindustries.agenten.workflow.WorkflowLoader;
import com.zuluindustries.agenten.workflow.WorkflowRunRepository;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * Zusammensetzung der Plattform (Composition Root): lädt Definitionen aus dem
 * Projektverzeichnis und verdrahtet Registries, Provider, Runtime und Workflow-Engine.
 * Fachmodule kommen über {@link PlatformExtension} hinzu; der Kern kennt sie nicht.
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
    private final AgentDefinitionWriter definitionWriter;
    private final AgentBuilder agentBuilder;
    private final Coordinator coordinator;
    private final WorkflowActionRegistry actions;
    private final WorkflowEngine workflows;
    private final ServiceRegistry services;
    private final Function<String, String> environment;
    private final Clock clock;
    private final List<PlatformExtension> extensions;

    private Platform(Builder builder) {
        this.settings = builder.settings;
        this.environment = builder.environment;
        this.clock = builder.clock;
        this.extensions = List.copyOf(builder.extensions != null ? builder.extensions : discoverExtensions());
        Path root = settings.projectRoot();
        this.tools = builder.tools;
        for (PlatformExtension extension : extensions) {
            for (Tool tool : extension.tools()) {
                if (tools.contains(tool.name())) {
                    throw new IllegalArgumentException("Doppeltes Tool '" + tool.name() + "' (Modul "
                            + extension.name() + ").");
                }
                tools.register(tool);
            }
        }
        // write_agent_definition prüft gegen Profile, Tools und Richtlinien, die erst unten entstehen.
        this.definitionWriter = new AgentDefinitionWriter(root.resolve("agents").resolve("definitions"),
                this::definitionChecks);
        if (!tools.contains(AgentDefinitionWriter.TOOL_NAME)) {
            tools.register(definitionWriter.tool());
        }
        this.skills = new SkillRegistry(skillDefinitions(root, extensions));
        this.agents = new AgentRegistry(agentDefinitions(root, extensions));
        this.catalog = builder.catalog != null ? builder.catalog : ModelCatalog.load(root);
        this.profiles = catalog.profiles();
        this.costs = new CostCalculator(catalog.pricing(), builder.clock);
        AgentPolicy policy = new AgentPolicy(tools, root, settings.agentTimezone(), builder.clock);
        AgentRuntime runtime = new AgentRuntime(tools, skills, policy);
        this.runner = new AgentRunner(runtime, profiles, builder.providers);
        this.agentBuilder = new AgentBuilder(agents, policy, definitionWriter);
        this.coordinator = new Coordinator(agents, agentBuilder, policy, profiles, tools);
        this.actions = builder.actions;
        this.services = builder.services;
        AgentStepExecutor executor = new AgentStepExecutor() {
            @Override
            public boolean knows(String agentName) {
                return agents.contains(agentName);
            }

            @Override
            public AgentResult run(String agentName, String prompt) {
                return runAgent(agents.get(agentName, settings.defaultModelProfile()), prompt, null);
            }
        };
        this.workflows = new WorkflowEngine(builder.repository, workflowDefinitions(root, extensions),
                actions, executor, costs, builder.clock);
    }

    private static List<PlatformExtension> discoverExtensions() {
        List<PlatformExtension> found = new ArrayList<>();
        ServiceLoader.load(PlatformExtension.class).forEach(found::add);
        return found;
    }

    /** Agenten aus {@code agents/definitions} und aus den Fachmodulen; doppelte IDs sind ein Fehler. */
    private static Map<String, AgentDefinition> agentDefinitions(Path root, List<PlatformExtension> extensions) {
        Map<String, AgentDefinition> definitions = new LinkedHashMap<>(
                AgentDefinitionLoader.loadAll(root.resolve("agents").resolve("definitions")));
        for (PlatformExtension extension : extensions) {
            extension.agentDefinitions().forEach((id, yaml) -> {
                AgentDefinition definition = AgentDefinitionLoader.parse(yaml, id,
                        "Modul " + extension.name() + ": " + id);
                if (!definition.enabled()) {
                    return;
                }
                if (definitions.putIfAbsent(definition.id(), definition) != null) {
                    throw new IllegalArgumentException("Doppelte Agent-ID '" + definition.id() + "' (Modul "
                            + extension.name() + ").");
                }
            });
        }
        return definitions;
    }

    /** Skills aus {@code skills/} und aus den Fachmodulen; doppelte Namen sind ein Fehler. */
    private static List<Skill> skillDefinitions(Path root, List<PlatformExtension> extensions) {
        Map<String, Skill> skills = new LinkedHashMap<>();
        SkillLoader.discover(root.resolve("skills")).forEach(skill -> skills.put(skill.name(), skill));
        for (PlatformExtension extension : extensions) {
            extension.skillDefinitions().forEach((name, content) -> {
                String source = "Modul " + extension.name() + ": " + name;
                Skill skill = SkillLoader.parse(content, null, source);
                if (skill == null) {
                    throw new IllegalArgumentException("Ungültige SKILL.md (" + source + ").");
                }
                if (skills.putIfAbsent(skill.name(), skill) != null) {
                    throw new IllegalArgumentException("Doppelter Skill '" + skill.name() + "' (Modul "
                            + extension.name() + ").");
                }
            });
        }
        return List.copyOf(skills.values());
    }

    /** Workflows aus {@code workflows/definitions} und aus den Fachmodulen; doppelte Schlüssel sind ein Fehler. */
    private static Map<String, WorkflowDefinition> workflowDefinitions(Path root,
                                                                       List<PlatformExtension> extensions) {
        Map<String, WorkflowDefinition> definitions = new LinkedHashMap<>(
                WorkflowLoader.loadAll(root.resolve("workflows").resolve("definitions")));
        for (PlatformExtension extension : extensions) {
            extension.workflowDefinitions().forEach((key, yaml) -> {
                WorkflowDefinition definition = WorkflowLoader.parse(yaml, "Modul " + extension.name() + ": " + key);
                if (definitions.putIfAbsent(key, definition) != null) {
                    throw new IllegalArgumentException("Workflow doppelt definiert: " + key + " (Modul "
                            + extension.name() + ").");
                }
            });
        }
        return definitions;
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

    private AgentDefinitionWriter.Checks definitionChecks() {
        return new AgentDefinitionWriter.Checks(profiles, runner.runtime().capabilities(), runner.runtime().policy());
    }

    public AgentDefinitionWriter definitionWriter() {
        return definitionWriter;
    }

    public AgentBuilder agentBuilder() {
        return agentBuilder;
    }

    public Coordinator coordinator() {
        return coordinator;
    }

    /** Ein Coordinator-Lauf mit den Standardgrenzen; {@code userInput} ist JSON mit task und capabilities. */
    public Coordinator.Result runCoordinator(String userInput) {
        int maxSteps = settings.maxAgentSteps();
        return coordinator.run(userInput, (nested, input) -> runner.run(nested, input, maxSteps),
                Coordinator.Limits.defaults());
    }

    /**
     * Führt einen Agenten aus; entspricht {@code run_configured_agent}. Der
     * {@code agent_builder} und der {@code coordinator} laufen dabei über ihre feste
     * Steuerung statt über ein Modell mit Werkzeugen.
     */
    public AgentResult runAgent(AgentDefinition agent, String userInput, Conversation conversation) {
        int maxSteps = settings.maxAgentSteps();
        if (Coordinator.AGENT_ID.equals(agent.id())) {
            return runCoordinator(userInput).asAgentResult();
        }
        if (AgentBuilder.AGENT_ID.equals(agent.id())) {
            return agentBuilder.run(agent, userInput, (nested, input) -> runner.run(nested, input, maxSteps), false)
                    .result();
        }
        return runner.run(agent, userInput, maxSteps, conversation);
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

    /** Umgebungsvariablen (einschließlich {@code .env}), z. B. für Zugangsdaten der Fachmodule. */
    public Function<String, String> environment() {
        return environment;
    }

    public Clock clock() {
        return clock;
    }

    /** Die eingebundenen Fachmodule in Ladereihenfolge. */
    public List<PlatformExtension> extensions() {
        return extensions;
    }

    /** Das Fachmodul der angegebenen Klasse, sofern eingebunden. */
    public <T extends PlatformExtension> Optional<T> extension(Class<T> type) {
        return extensions.stream().filter(type::isInstance).map(type::cast).findFirst();
    }

    /** Alle Kommandozeilenbefehle der Fachmodule. */
    public List<CliCommand> commands() {
        return extensions.stream().flatMap(extension -> extension.commands().stream()).toList();
    }

    /** Konfigurierbarer Aufbau; Standardwerte entsprechen dem Python-Projekt. */
    public static final class Builder {

        private final Settings settings;
        private final Function<String, String> environment;
        private ToolRegistry tools;
        private ProviderRegistry providers;
        private ModelCatalog catalog;
        private WorkflowActionRegistry actions = new WorkflowActionRegistry();
        private WorkflowRunRepository repository = new InMemoryWorkflowRunRepository();
        private ServiceRegistry services = new ServiceRegistry();
        private List<PlatformExtension> extensions;
        private Clock clock = Clock.systemUTC();

        private Builder(Settings settings, Function<String, String> environment) {
            this.settings = settings;
            this.environment = environment;
            this.tools = new ToolRegistry();
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

        /**
         * Fachmodule ausdrücklich festlegen; ohne Angabe werden alle über
         * {@link ServiceLoader} gefundenen Module eingebunden.
         */
        public Builder extensions(List<? extends PlatformExtension> value) {
            this.extensions = List.copyOf(value);
            return this;
        }

        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        public Platform build() {
            Platform platform = new Platform(this);
            for (PlatformExtension extension : platform.extensions) {
                extension.install(platform);
            }
            return platform;
        }
    }
}
