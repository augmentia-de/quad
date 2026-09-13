package de.augmentia.quad.core.agent.strategies;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Reflexion strategy: Iterative self-correction until an evaluator accepts the response.
 * <p>
 * Source: Python {@code src/quad/strategies/reflexion.py}
 */
public class ReflexionStrategy {

    private final ChatModel chatModel;

    public ReflexionStrategy(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @FunctionalInterface
    public interface Evaluator {
        boolean test(String result);
    }

    public String execute(String task, Evaluator evaluator, int maxReflections) {
        List<ChatMessage> history = new ArrayList<>(List.of(UserMessage.from(task)));

        for (int i = 0; i < maxReflections; i++) {
            String candidate = chatModel.chat(ChatRequest.builder().messages(history).build())
                    .aiMessage().text();

            if (evaluator.test(candidate)) {
                return candidate;
            }

            history.add(AiMessage.from(candidate));
            history.add(UserMessage.from(
                    "Reflect: The previous answer was incomplete or incorrect. " +
                    "Correct it and output the improved version as plain text."));
        }
        throw new IllegalStateException(
                "Reflexion strategy failed after " + maxReflections + " reflections");
    }
}
