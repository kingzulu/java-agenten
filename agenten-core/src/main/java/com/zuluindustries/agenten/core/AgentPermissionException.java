package com.zuluindustries.agenten.core;

/** Eine Aktion ist für den Agenten nicht erlaubt (entspricht {@code PermissionError} in Python). */
public class AgentPermissionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AgentPermissionException(String message) {
        super(message);
    }
}
