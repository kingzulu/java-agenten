package com.zuluindustries.agenten.core;

import com.zuluindustries.agenten.model.ModelResponse;

/** Das Zeitlimit des Agenten wurde überschritten. */
public class AgentTimeoutException extends AgentException {

    private static final long serialVersionUID = 1L;

    private final transient ModelResponse lateResponse;

    public AgentTimeoutException() {
        this(null);
    }

    /** @param lateResponse eine Antwort, die erst nach Ablauf des Zeitlimits eintraf (für die Kostenerfassung) */
    public AgentTimeoutException(ModelResponse lateResponse) {
        super("Zeitlimit des Agenten überschritten.");
        this.lateResponse = lateResponse;
    }

    public ModelResponse lateResponse() {
        return lateResponse;
    }
}
