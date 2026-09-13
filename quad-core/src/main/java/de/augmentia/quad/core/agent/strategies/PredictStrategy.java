package de.augmentia.quad.core.agent.strategies;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

/**
 * Predict strategy: Single-shot LLM call, optionally with structured output.
 * <p>
 * Source: Python {@code src/quad/strategies/predict.py}
 */
public class PredictStrategy {

    private final ChatModel chatModel;

    public PredictStrategy(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String execute(String systemPrompt, String userPrompt) {
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(List.of(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt)))
                .build());
        return response.aiMessage().text();
    }

    /**
     * Executes a predict call and parses the result into the target type.
     * The LLM is instructed to return JSON matching the simple class name.
     */
    public <T> T executeWithValidation(String systemPrompt, String userPrompt, Class<T> outputType) {
        String instruction = "\n\nReturn result as JSON matching " + outputType.getSimpleName();
        String raw = execute(systemPrompt, userPrompt + instruction);
        return parseJson(raw, outputType);
    }

    private <T> T parseJson(String raw, Class<T> outputType) {
        String clean = raw.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        try {
            return new ObjectMapper().readValue(clean, outputType);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Could not parse response as " + outputType.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
