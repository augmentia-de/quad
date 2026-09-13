package de.augmentia.quad.core.session.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Session memory: holds the chat message history per {@code AgentSessionState}.
 * When the window ({@link #maxMessages}) is exceeded, the oldest messages
 * are NOT discarded but compressed into a textual summary —
 * nothing is lost.
 */
public class SessionMemory {

    private final int maxMessages;
    private final List<ChatMessage> history = new ArrayList<>();
    private String summary;

    public SessionMemory() {
        this(50);
    }

    public SessionMemory(int maxMessages) {
        if (maxMessages <= 0) throw new IllegalArgumentException("maxMessages must be > 0");
        this.maxMessages = maxMessages;
    }

    public int maxMessages() { return maxMessages; }

    /** Appends new messages; overflow is compressed into the summary instead of discarded */
    public synchronized void record(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        history.addAll(messages);
        int overflow = history.size() - maxMessages;
        if (overflow > 0) {
            List<ChatMessage> oldest = new ArrayList<>(history.subList(0, overflow));
            history.subList(0, overflow).clear();
            summary = foldIntoSummary(oldest);
        }
    }

    public String summary() { return summary; }

    public boolean hasSummary() { return summary != null && !summary.isBlank(); }

    public synchronized List<ChatMessage> history() { return List.copyOf(history); }

    public synchronized void clear() {
        history.clear();
        summary = null;
    }

    private String foldIntoSummary(List<ChatMessage> oldest) {
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            sb.append(summary).append("\n");
        }
        sb.append("Previously summarized messages:\n");
        for (ChatMessage m : oldest) {
            String role = switch (m.type()) {
                case SYSTEM -> "System";
                case USER -> "User";
                case AI -> "Assistant";
                case TOOL_EXECUTION_RESULT -> "Tool";
                default -> "?";
            };
            String text = m.toString();
            if (text.length() > 200) text = text.substring(0, 200) + "...";
            sb.append("[").append(role).append("] ").append(text).append("\n");
        }
        return sb.toString();
    }
}