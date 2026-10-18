package com.zuluindustries.agenten.core;

import com.zuluindustries.agenten.model.Message;

import java.util.ArrayList;
import java.util.List;

/** Sitzungsbezogener Gesprächsverlauf mit begrenzter Anzahl Turns; entspricht {@code core/conversation.py}. */
public final class Conversation {

    private record Turn(Message user, Message assistant) {
    }

    private final List<Turn> turns = new ArrayList<>();
    private final Integer maxTurns;
    private Message currentUserMessage;

    public Conversation() {
        this(5);
    }

    /** @param maxTurns maximale Anzahl gespeicherter Turns; {@code null} für unbegrenzt */
    public Conversation(Integer maxTurns) {
        this.maxTurns = maxTurns;
    }

    public void addUserMessage(String content) {
        currentUserMessage = Message.user(content);
    }

    public void addAssistantMessage(String content) {
        if (currentUserMessage == null) {
            throw new IllegalStateException("Keine Benutzer-Nachricht für diesen Turn vorhanden.");
        }
        turns.add(new Turn(currentUserMessage, Message.assistant(content)));
        currentUserMessage = null;
        trim();
    }

    public List<Message> messages() {
        List<Message> messages = new ArrayList<>();
        for (Turn turn : turns) {
            messages.add(turn.user());
            messages.add(turn.assistant());
        }
        if (currentUserMessage != null) {
            messages.add(currentUserMessage);
        }
        return messages;
    }

    public void clear() {
        turns.clear();
        currentUserMessage = null;
    }

    private void trim() {
        if (maxTurns != null && turns.size() > maxTurns) {
            turns.subList(0, turns.size() - maxTurns).clear();
        }
    }
}
