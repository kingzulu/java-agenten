package com.zuluindustries.agenten.core;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.agent.ExecutionConfig;
import com.zuluindustries.agenten.agent.PermissionConfig;
import com.zuluindustries.agenten.agent.ToolConfig;
import com.zuluindustries.agenten.model.Message;
import com.zuluindustries.agenten.model.ModelProvider;
import com.zuluindustries.agenten.model.ModelRequest;
import com.zuluindustries.agenten.model.ModelResponse;
import com.zuluindustries.agenten.model.ToolCall;
import com.zuluindustries.agenten.model.ToolStrategy;
import com.zuluindustries.agenten.skill.Skill;
import com.zuluindustries.agenten.skill.SkillRegistry;
import com.zuluindustries.agenten.tool.Tool;
import com.zuluindustries.agenten.tool.ToolCategory;
import com.zuluindustries.agenten.tool.ToolRegistry;
import com.zuluindustries.agenten.tool.ToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Agentic Loop eines einzelnen Agenten-Turns; entspricht {@code core/runtime.py}.
 *
 * <p>Ablauf: Instruktionen aufbauen, Tools vorbereiten, ggf. Skill-Routing,
 * Modell aufrufen, angeforderte Tools nach Berechtigungsprüfung ausführen,
 * Ergebnisse zurückgeben, bis das Modell keinen Toolaufruf mehr anfordert.
 * Tokenverbrauch wird je Modellaufruf erfasst.</p>
 */
public final class AgentRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(AgentRuntime.class);

    private final ToolRegistry tools;
    private final SkillRegistry skills;
    private final AgentPolicy policy;
    private final Capabilities capabilities;
    private final LongSupplier nanoTime;

    public AgentRuntime(ToolRegistry tools, SkillRegistry skills, AgentPolicy policy) {
        this(tools, skills, policy, System::nanoTime);
    }

    public AgentRuntime(ToolRegistry tools, SkillRegistry skills, AgentPolicy policy, LongSupplier nanoTime) {
        this.tools = tools;
        this.skills = skills;
        this.policy = policy;
        this.capabilities = new Capabilities(tools, skills);
        this.nanoTime = nanoTime;
    }

    public AgentPolicy policy() {
        return policy;
    }

    public Capabilities capabilities() {
        return capabilities;
    }

    /**
     * Führt einen Turn aus. Konfigurationsfehler (unbekannte Tools oder Skills)
     * werden sofort geworfen. Laufzeitfehler führen – außer bei
     * {@code execution.stop_on_error} – zu einem Ergebnis mit gesetztem {@code error}.
     */
    public AgentResult runTurn(ModelProvider provider, String model, AgentDefinition agent,
                               String userInput, Conversation conversation, int defaultMaxSteps) {
        List<String> allowedTools = policy.effectiveAllowedTools(agent);
        Capabilities.Prepared prepared = capabilities.prepare(allowedTools, agent.skills());

        Turn turn = new Turn(provider, model, agent, allowedTools, prepared.tools(), userInput, conversation,
                defaultMaxSteps);
        try {
            return turn.run();
        } catch (RuntimeException error) {
            if (agent.execution().stopOnError()) {
                throw error;
            }
            if (error instanceof AgentTimeoutException timeout && timeout.lateResponse() != null) {
                turn.modelCalls.add(ModelCall.of(turn.modelCalls.size(), turn.modelCalls.size(),
                        "late_response", timeout.lateResponse().usage()));
            }
            String message = messageOf(error);
            LOG.warn("[trace={}] Agent turn failed error={}", turn.traceId, message);
            return turn.result("Agentenfehler: " + message, message);
        }
    }

    static String messageOf(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    private final class Turn {

        final String traceId = UUID.randomUUID().toString().replace("-", "");
        final List<ModelCall> modelCalls = new ArrayList<>();
        final long startedAt = nanoTime.getAsLong();

        private final ModelProvider provider;
        private final String model;
        private final AgentDefinition agent;
        private final List<String> allowedTools;
        private final List<ToolSpec> agentTools;
        private final String userInput;
        private final Conversation conversation;
        private final int maxSteps;
        private final long deadline;
        private final int retryCount;
        private final Duration requestTimeout;

        Turn(ModelProvider provider, String model, AgentDefinition agent, List<String> allowedTools,
             List<ToolSpec> agentTools, String userInput, Conversation conversation, int defaultMaxSteps) {
            this.provider = provider;
            this.model = model;
            this.agent = agent;
            this.allowedTools = allowedTools;
            this.agentTools = agentTools;
            this.userInput = userInput;
            this.conversation = conversation;
            ExecutionConfig execution = agent.execution();
            this.maxSteps = execution.maxToolSteps() != null ? execution.maxToolSteps() : defaultMaxSteps;
            this.deadline = startedAt + Duration.ofSeconds(execution.timeoutSeconds()).toNanos();
            this.retryCount = execution.effectiveRetryCount();
            this.requestTimeout = Duration.ofSeconds(execution.timeoutSeconds());
        }

        AgentResult run() {
            LOG.info("[trace={}] Agent turn started model={}", traceId, model);

            ExecutionConfig execution = agent.execution();
            PermissionConfig permissions = agent.permissions();
            ToolConfig toolConfig = agent.tools();
            ToolStrategy configuredStrategy = ToolStrategy.fromConfig(toolConfig.strategy());
            int maxIterations = execution.effectiveMaxIterations();
            int step = 0;
            int iteration = 1;

            String instructions = policy.buildInstructions(agent, userInput);

            Set<String> allowedSet = new HashSet<>(allowedTools);
            List<ToolSpec> activeTools = agentTools.stream()
                    .filter(tool -> allowedSet.contains(tool.name())
                            || SkillRegistry.ACTIVATE_SKILL_TOOL.equals(tool.name()))
                    .toList();
            if (!permissions.toolUse() || configuredStrategy == ToolStrategy.MANUAL) {
                activeTools = List.of();
            }

            List<Skill> availableSkills = skills.available(agent.skills());
            boolean routingRequired = availableSkills.size() > 1;
            String activeSkill = null;
            if (availableSkills.size() == 1) {
                activeSkill = availableSkills.getFirst().name();
                instructions = instructions + "\n\n" + skills.activateAllowed(activeSkill, agent.skills());
            }

            List<Message> input;
            if (conversation != null) {
                conversation.addUserMessage(userInput);
                input = conversation.messages();
            } else {
                input = List.of(Message.user(userInput));
            }

            ToolStrategy initialStrategy = routingRequired && !activeTools.isEmpty()
                    ? ToolStrategy.REQUIRED : configuredStrategy;
            ModelRequest initialRequest = request(instructions, input, activeTools, initialStrategy);
            ModelResponse response = callWithRetries(() -> provider.createResponse(initialRequest));
            recordCall(0, "initial", response);

            String finalText;
            String outputError = null;

            while (true) {
                if (step >= maxSteps) {
                    finalText = "Der Agent wurde beendet, weil die maximale "
                            + "Anzahl der Verarbeitungsschritte erreicht wurde.";
                    break;
                }

                List<ToolCall> toolCalls = response.toolCalls();

                if (routingRequired && activeSkill == null
                        && (toolCalls.size() != 1
                        || !SkillRegistry.ACTIVATE_SKILL_TOOL.equals(toolCalls.getFirst().name()))) {
                    throw new AgentException("Der Skill-Routing-Schritt muss genau einen Skill auswählen.");
                }

                if (toolCalls.isEmpty()) {
                    String candidate = response.outputText();
                    try {
                        policy.validateOutput(candidate, agent.output());
                    } catch (IllegalArgumentException error) {
                        if (iteration >= maxIterations) {
                            if (execution.stopOnError()) {
                                throw error;
                            }
                            outputError = error.getMessage();
                            finalText = "Agentenfehler: " + error.getMessage();
                            break;
                        }
                        iteration++;
                        String correction = userInput + "\n\n"
                                + "Der vorherige Lösungsversuch war ungültig: "
                                + error.getMessage() + ". Erzeuge eine korrigierte Ausgabe.";
                        ModelRequest correctionRequest = request(instructions, List.of(Message.user(correction)),
                                activeTools, configuredStrategy);
                        response = callWithRetries(() -> provider.createResponse(correctionRequest));
                        recordCall(step, "iteration", response);
                        continue;
                    }
                    finalText = candidate;
                    if (conversation != null) {
                        conversation.addAssistantMessage(finalText);
                    }
                    break;
                }

                List<Object> results = new ArrayList<>();
                step++;

                for (ToolCall toolCall : toolCalls) {
                    String toolName = toolCall.name();

                    if (SkillRegistry.ACTIVATE_SKILL_TOOL.equals(toolName)) {
                        if (!routingRequired || activeSkill != null) {
                            throw new AgentPermissionException("Skill-Auswahl ist in diesem Zustand nicht erlaubt.");
                        }
                        Object requested = toolCall.arguments().get("name");
                        String skillName = requested == null ? null : requested.toString();
                        LOG.info("[trace={}] Skill activation started skill={}", traceId, skillName);
                        String skillInstructions = skills.activateAllowed(skillName, agent.skills());
                        activeSkill = skillName;
                        activeTools = capabilities.skillTools(skills.find(skillName).orElseThrow(), allowedTools);
                        results.add(skillInstructions);
                        LOG.info("[trace={}] Skill activated skill={}", traceId, skillName);
                        continue;
                    }

                    Tool tool = checkToolCall(toolName);
                    long toolStarted = nanoTime.getAsLong();
                    LOG.info("[trace={}] Tool started tool={}", traceId, toolName);
                    Object result;
                    try {
                        result = tool.execute(toolCall.arguments());
                    } catch (RuntimeException error) {
                        LOG.error("[trace={}] Tool failed tool={}", traceId, toolName, error);
                        throw error;
                    }
                    LOG.info("[trace={}] Tool completed tool={} duration_ms={}", traceId, toolName,
                            String.format("%.2f", (nanoTime.getAsLong() - toolStarted) / 1_000_000.0));
                    results.add(result);
                }

                ModelResponse previous = response;
                ModelRequest followupRequest = request(instructions, List.of(), activeTools, configuredStrategy);
                response = callWithRetries(() -> provider.createFollowup(followupRequest, previous, toolCalls, results));
                recordCall(step, "followup", response);
            }

            AgentResult result = result(finalText, outputError);
            LOG.info("[trace={}] Agent turn completed provider={} model={} duration_ms={} input_tokens={} "
                            + "output_tokens={} total_tokens={}", traceId, provider.name(), model,
                    String.format("%.2f", result.usage().durationMs()), result.inputTokens(),
                    result.outputTokens(), result.totalTokens());
            return result;
        }

        /** Prüft Registrierung, Freigabe, Sperren, Kategorien und Bestätigungspflicht eines Toolaufrufs. */
        private Tool checkToolCall(String toolName) {
            PermissionConfig permissions = agent.permissions();
            Tool tool = tools.find(toolName).orElse(null);
            if (tool == null) {
                LOG.error("[trace={}] Unknown tool tool={}", traceId, toolName);
                throw new AgentException("Tool '" + toolName + "' ist nicht registriert.");
            }
            if (!allowedTools.contains(toolName)) {
                LOG.warn("[trace={}] Tool blocked tool={} reason=not_allowed", traceId, toolName);
                throw new AgentPermissionException("Tool '" + toolName + "' ist für diesen Agenten nicht erlaubt.");
            }
            if (agent.tools().denied().contains(toolName)) {
                throw new AgentPermissionException("Tool '" + toolName + "' ist ausdrücklich gesperrt.");
            }
            Set<ToolCategory> categories = tool.categories().isEmpty()
                    ? EnumSet.noneOf(ToolCategory.class) : EnumSet.copyOf(tool.categories());
            if (categories.contains(ToolCategory.DELETE) && !permissions.deleteOperations()) {
                throw new AgentPermissionException("Löschendes Tool '" + toolName + "' wurde blockiert.");
            }
            if (categories.contains(ToolCategory.EXTERNAL) && !permissions.externalAccess()) {
                throw new AgentPermissionException("Externes Tool '" + toolName + "' wurde blockiert.");
            }

            Set<String> confirmationCategories = new HashSet<>();
            confirmationCategories.add(toolName);
            if (categories.contains(ToolCategory.WRITE)) {
                confirmationCategories.add("write");
            }
            if (categories.contains(ToolCategory.DELETE)) {
                confirmationCategories.add("delete");
            }
            if (categories.contains(ToolCategory.EXTERNAL)) {
                confirmationCategories.add("external");
                if (categories.contains(ToolCategory.WRITE)) {
                    confirmationCategories.add("external_write");
                }
            }
            for (String required : permissions.requireConfirmationFor()) {
                if (confirmationCategories.contains(required)) {
                    throw new AgentPermissionException("Tool '" + toolName + "' benötigt eine Bestätigung; "
                            + "ein Bestätigungsdialog ist noch nicht eingerichtet.");
                }
            }

            if (categories.contains(ToolCategory.WRITE) && !permissions.writeOperations()) {
                LOG.warn("[trace={}] Tool blocked tool={} reason=write_tool", traceId, toolName);
                throw new AgentPermissionException("Schreibendes Tool '" + toolName + "' wurde blockiert.");
            }
            return tool;
        }

        private ModelRequest request(String instructions, List<Message> input, List<ToolSpec> activeTools,
                                     ToolStrategy strategy) {
            return new ModelRequest(model, instructions, input, activeTools, agent.model(), strategy, requestTimeout);
        }

        private void recordCall(int step, String callType, ModelResponse response) {
            int callIndex = modelCalls.size();
            ModelCall call = ModelCall.of(callIndex, step, callType, response.usage());
            modelCalls.add(call);
            LOG.info("[trace={}] Model call completed call_index={} step={} call_type={} input_tokens={} "
                            + "output_tokens={} total_tokens={}", traceId, callIndex, step, callType,
                    call.inputTokens(), call.outputTokens(), call.totalTokens());
        }

        private ModelResponse callWithRetries(Supplier<ModelResponse> call) {
            for (int attempt = 0; ; attempt++) {
                if (nanoTime.getAsLong() >= deadline) {
                    throw new AgentTimeoutException();
                }
                try {
                    ModelResponse response = call.get();
                    if (nanoTime.getAsLong() >= deadline) {
                        throw new AgentTimeoutException(response);
                    }
                    return response;
                } catch (AgentTimeoutException error) {
                    throw error;
                } catch (RuntimeException error) {
                    if (attempt >= retryCount) {
                        throw error;
                    }
                    LOG.warn("Provider call failed; retrying attempt={} error={}", attempt + 1, messageOf(error));
                }
            }
        }

        AgentResult result(String text, String error) {
            long in = modelCalls.stream().mapToLong(ModelCall::inputTokens).sum();
            long out = modelCalls.stream().mapToLong(ModelCall::outputTokens).sum();
            long total = modelCalls.stream().mapToLong(ModelCall::totalTokens).sum();
            double durationMs = (nanoTime.getAsLong() - startedAt) / 1_000_000.0;
            UsageRecord usage = new UsageRecord(traceId, provider.name(), model, in, out, total, durationMs,
                    modelCalls);
            return new AgentResult(traceId, text, usage, in, out, total, error, provider.name(), model, null);
        }
    }
}
