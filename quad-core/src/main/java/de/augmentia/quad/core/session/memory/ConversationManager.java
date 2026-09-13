package de.augmentia.quad.core.session.memory;

import de.augmentia.quad.core.message.Message;
import java.util.List;

public sealed interface ConversationManager
    permits SlidingWindowConversationManager, SummarizingSlidingWindowConversationManager {

    List<Message> prune(List<Message> messages);
}
