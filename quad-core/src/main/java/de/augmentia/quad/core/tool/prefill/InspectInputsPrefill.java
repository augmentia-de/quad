package de.augmentia.quad.core.tool.prefill;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/**
 * Prefill support: creates prefilled messages and estimates the
 * token count of the context window.
 * <p>
 * Source: Python {@code src/quad/strategies/prefill.py}
 */
public final class InspectInputsPrefill {

    private InspectInputsPrefill() {
    }

    /**
     * Creates a prefilled message sequence (user prompt + fixed AI prefill)
     * to estimate the prompt length before full generation.
     */
    public static List<ChatMessage> prefill(String userPrompt, String prefillText) {
        return List.of(
                UserMessage.from(userPrompt),
                AiMessage.from(prefillText)
        );
    }

    /**
     * Rough token estimate across all messages (4 characters per token).
     */
    public static int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        if (messages == null) {
            return 0;
        }
        for (ChatMessage message : messages) {
            total += textOf(message).length() / 4;
        }
        return total;
    }

    private static String textOf(ChatMessage message) {
        if (message instanceof AiMessage ai) {
            return ai.text() != null ? ai.text() : "";
        }
        if (message instanceof SystemMessage system) {
            return system.text();
        }
        if (message instanceof UserMessage user) {
            try {
                return user.singleText();
            } catch (RuntimeException e) {
                return user.toString();
            }
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            return toolResult.text();
        }
        return message.toString();
    }
}
