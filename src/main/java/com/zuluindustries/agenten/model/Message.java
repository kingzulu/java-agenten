package com.zuluindustries.agenten.model;

/** Eine Gesprächsnachricht ({@code user} oder {@code assistant}). */
public record Message(String role, String content) {

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }
}
