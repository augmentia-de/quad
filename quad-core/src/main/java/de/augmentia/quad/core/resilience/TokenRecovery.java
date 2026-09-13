package de.augmentia.quad.core.resilience;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;
import java.util.List;

public class TokenRecovery {

    private static final int MAX_ATTEMPTS = 3;

    private int attempts;

    public TokenRecovery() {
        this.attempts = 0;
    }

    public static boolean isTokenLimitError(Exception e) {
        if (e == null) return false;
        var msg = e.getMessage();
        if (msg == null) return false;
        var lower = msg.toLowerCase();
        return lower.contains("maximum context length")
            || lower.contains("context_length_exceeded")
            || lower.contains("max_tokens")
            || lower.contains("token limit")
            || lower.contains("too many tokens")
            || lower.contains("request too large")
            || (lower.contains("400") && lower.contains("context"));
    }

    public boolean recover(ChatMemory chatMemory) {
        if (attempts >= MAX_ATTEMPTS) return false;
        attempts++;

        var messages = chatMemory.messages();
        if (messages.size() <= 1) return false;

        var systemMsgs = messages.stream()
            .filter(m -> m instanceof SystemMessage)
            .toList();
        var nonSystemMsgs = messages.stream()
            .filter(m -> !(m instanceof SystemMessage))
            .toList();

        if (nonSystemMsgs.size() <= 1) return false;

        int keepNonSystem = Math.max(2, nonSystemMsgs.size() / 2);
        var keptNonSystem = nonSystemMsgs.subList(nonSystemMsgs.size() - keepNonSystem, nonSystemMsgs.size());

        chatMemory.clear();
        for (var msg : systemMsgs) chatMemory.add(msg);
        for (var msg : keptNonSystem) chatMemory.add(msg);

        return true;
    }

    public int attempts() {
        return attempts;
    }

    public void reset() {
        attempts = 0;
    }
}
