package com.zuluindustries.agenten.core;

/** Laufzeitfehler eines Agenten (entspricht {@code RuntimeError} in Python). */
public class AgentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
