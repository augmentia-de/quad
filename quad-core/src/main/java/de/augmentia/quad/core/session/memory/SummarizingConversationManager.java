package de.augmentia.quad.core.session.memory;

import java.util.List;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.capability.doc.AgentDocExtractor;
import dev.langchain4j.data.message.ChatMessage;

public class SummarizingConversationManager {
    private final AgentDocExtractor docExtractor;
    private int maxMessages = 50;

    public SummarizingConversationManager(AgentDocExtractor docExtractor) {
        this.docExtractor = docExtractor;
    }

    public SummarizingConversationManager(AgentDocExtractor docExtractor, int maxMessages) {
        this.docExtractor = docExtractor;
        this.maxMessages = maxMessages;
    }

    public List<ChatMessage> prune(List<ChatMessage> messages, AgentSessionState state) {
        if (messages.size() <= maxMessages) {
            return messages;
        }
        return messages.subList(messages.size() - maxMessages, messages.size());
    }

    public String summarize(List<ChatMessage> messages, AgentSessionState state) {
        StringBuilder summary = new StringBuilder();
        summary.append("Conversation summary (").append(messages.size()).append(" messages):\n");
        for (ChatMessage msg : messages) {
            String role = switch (msg.type()) {
                case SYSTEM -> "System";
                case USER -> "User";
                case AI -> "Assistant";
                case TOOL_EXECUTION_RESULT -> "Tool";
                case CUSTOM -> "custom";
            };
            String text = msg.toString();
            if (text != null && text.length() > 200) {
                text = text.substring(0, 200) + "...";
            }
            summary.append("[").append(role).append("] ").append(text).append("\n");
        }
        return summary.toString();
    }
}