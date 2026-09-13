package de.augmentia.quad.core.session.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.data.message.ChatMessage;

public class AgentMemory implements ChatMemory {
    private final ChatMemory delegate;

    public AgentMemory(ChatMemory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public void add(ChatMessage chatMessage) {
        delegate.add(chatMessage);
    }

    @Override
    public java.util.List<ChatMessage> messages() {
        return delegate.messages();
    }

    @Override
    public void clear() {
        delegate.clear();
    }

}