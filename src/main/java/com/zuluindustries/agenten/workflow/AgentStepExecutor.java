package com.zuluindustries.agenten.workflow;

import com.zuluindustries.agenten.core.AgentResult;

/** Verbindet Agentenschritte mit der Agenten-Runtime. */
public interface AgentStepExecutor {

    boolean knows(String agentName);

    AgentResult run(String agentName, String prompt);
}
