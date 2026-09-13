package de.augmentia.quad.core.session.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class MultiSystemMessageChatMemory implements ChatMemory {

    private final String id = UUID.randomUUID().toString();
    private final int maxNonSystemMessages;
    private final List<ChatMessage> messages = new ArrayList<>();

    public MultiSystemMessageChatMemory(int maxNonSystemMessages) {
        if (maxNonSystemMessages < 1) {
            throw new IllegalArgumentException("maxNonSystemMessages must be >= 1, got: " + maxNonSystemMessages);
        }
        this.maxNonSystemMessages = maxNonSystemMessages;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public void add(ChatMessage message) {
        messages.add(message);
        prune();
    }

    @Override
    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    @Override
    public void clear() {
        messages.clear();
    }

    public void replaceFirstSystemMessage(ChatMessage newMessage) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                messages.set(i, newMessage);
                return;
            }
        }
    }

    private void prune() {
        int nonSystemCount = 0;
        for (var msg : messages) {
            if (!(msg instanceof SystemMessage)) nonSystemCount++;
        }
        while (nonSystemCount > maxNonSystemMessages) {
            int idx = indexOfFirstNonSystem();
            if (idx < 0) break;
            messages.remove(idx);
            nonSystemCount--;
        }
    }

    private int indexOfFirstNonSystem() {
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof SystemMessage)) return i;
        }
        return -1;
    }
}
