package com.zuluindustries.agenten.builder;

import com.zuluindustries.agenten.agent.AgentDefinition;
import com.zuluindustries.agenten.core.AgentResult;

import java.util.function.BiFunction;

/**
 * Zweistufige Erzeugung einer Agentendefinition; entspricht {@code agents/creator.py}.
 * Zuerst erstellt der prompt_agent den Prompt, danach baut der agent_creator_agent
 * daraus die vollständige YAML-Definition.
 */
public final class AgentCreator {

    /**
     * @param result          Ergebnis mit allen Modellaufrufen beider Schritte, damit Tokens
     *                        und Kosten vollständig erfasst sind
     * @param generatedPrompt Prompt des prompt_agent oder {@code null}, wenn er fehlschlug
     */
    public record Result(AgentResult result, String generatedPrompt) {
    }

    private AgentCreator() {
    }

    public static Result run(BiFunction<AgentDefinition, String, AgentResult> runner, AgentDefinition promptAgent,
                             AgentDefinition creatorAgent, String userInput) {
        AgentResult prompt = runner.apply(promptAgent, userInput);
        if (prompt.failed()) {
            return new Result(new AgentResult(prompt.traceId(), "Prompt-Erstellung fehlgeschlagen: " + prompt.text(),
                    prompt.usage(), prompt.inputTokens(), prompt.outputTokens(), prompt.totalTokens(),
                    prompt.error(), prompt.provider(), prompt.model(), prompt.modelProfile()), null);
        }
        String creatorInput = "Ursprünglicher Benutzerauftrag:\n"
                + userInput + "\n\n"
                + "Vom prompt_agent erzeugter Prompt; unverändert als description übernehmen:\n"
                + AgentTemplates.OPENING + "\n"
                + prompt.text() + "\n"
                + AgentTemplates.CLOSING;
        AgentResult creator = runner.apply(creatorAgent, creatorInput);
        return new Result(creator.withEarlierCalls(prompt.modelCalls()), prompt.text());
    }
}
