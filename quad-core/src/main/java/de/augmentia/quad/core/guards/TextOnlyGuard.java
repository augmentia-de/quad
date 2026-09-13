package de.augmentia.quad.core.guards;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/**
 * Prevents agent drift: detects repeated text-only responses in the CodeAct flow
 * and injects corrections or aborts after {@code maxTextOnly} failed attempts.
 * <p>
 * Source: Python {@code TextOnlyReply} event, {@code codeact.py} text-only handling
 */
public class TextOnlyGuard {

    private int consecutiveTextOnly = 0;
    private final int maxTextOnly;

    public TextOnlyGuard(int maxTextOnly) {
        if (maxTextOnly <= 0) {
            throw new IllegalArgumentException("maxTextOnly must be > 0");
        }
        this.maxTextOnly = maxTextOnly;
    }

    /**
     * Checks a response for text-only drift.
     *
     * @param aiMessage  the LLM response
     * @param messages   the message list into which a correction is injected if needed
     * @param methodName the invoked agent method name (for the error message)
     * @return {@code true} if the response is valid (tool call), otherwise {@code false}
     */
    private static final List<String> DONE_SIGNALS = List.of(
            "DONE", "FINISHED", "RESULT:", "COMPLETE");

    public boolean checkAndGuard(AiMessage aiMessage, List<ChatMessage> messages, String methodName) {
        if (aiMessage == null || !aiMessage.hasToolExecutionRequests()) {
            // Check if agent sent a done signal
            String text = aiMessage != null ? aiMessage.text() : "";
            if (isDoneSignal(text)) {
                consecutiveTextOnly = 0;
                return true;
            }

            consecutiveTextOnly++;

            if (consecutiveTextOnly >= maxTextOnly) {
                throw new IllegalStateException(
                        "Agent gave plain text " + maxTextOnly + " times in a row for " + methodName +
                        ". Must call a tool or send a done signal (DONE/RESULT:...).");
            }

            messages.add(UserMessage.from(
                    "Your response was plain text without a tool call. " +
                    "If you are done, provide the final result (e.g. with 'DONE' or 'RESULT:...'). " +
                    "If not, call an available tool."));
            return false;
        }

        consecutiveTextOnly = 0;
        return true;
    }

    private static boolean isDoneSignal(String text) {
        if (text == null || text.isBlank()) return false;
        String upper = text.strip().toUpperCase();
        return DONE_SIGNALS.stream().anyMatch(upper::contains);
    }

    public int consecutiveTextOnly() {
        return consecutiveTextOnly;
    }
}
