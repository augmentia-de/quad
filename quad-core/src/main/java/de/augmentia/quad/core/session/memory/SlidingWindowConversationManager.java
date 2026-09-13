package de.augmentia.quad.core.session.memory;

import de.augmentia.quad.core.message.Message;
import java.util.List;
import java.util.stream.Stream;

public record SlidingWindowConversationManager(int windowSize) implements ConversationManager {

    public SlidingWindowConversationManager {
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize must be >= 1, got: " + windowSize);
        }
    }

    @Override
    public List<Message> prune(List<Message> messages) {
        if (messages.size() <= windowSize) {
            return messages;
        }

        var systemMessages = messages.stream()
            .filter(Message::isSystem)
            .toList();
        var nonSystem = messages.stream()
            .filter(m -> !m.isSystem())
            .toList();

        if (nonSystem.size() <= windowSize) {
            return messages;
        }

        var keptNonSystem = nonSystem.subList(nonSystem.size() - windowSize, nonSystem.size());
        return Stream.concat(systemMessages.stream(), keptNonSystem.stream()).toList();
    }
}
