package de.augmentia.quad.core.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;

import java.util.List;

/**
 * Manages structured output: applies JSON schema to LLM requests,
 * parses responses, and forces valid JSON via retry prompt.
 * Extracted from AgentRuntime.java to separate output format concerns from orchestration.
 */
public class OutputForcer {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StructuredOutputConfig config;
    private String result;
    private boolean forceAttempted;

    public OutputForcer(StructuredOutputConfig config) {
        this.config = config;
        this.result = null;
        this.forceAttempted = false;
    }

    /**
     * Applies the structured output response format to a parameters builder,
     * used when ChatRequestParameters are already set.
     */
    @SuppressWarnings("unchecked")
    public void applyToParams(DefaultChatRequestParameters.Builder<?> paramsBuilder) {
        if (config != null && config.isEnabled()) {
            var jsonSchema = config.effectiveJsonSchema();
            if (jsonSchema != null) {
                paramsBuilder.responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(jsonSchema)
                        .build());
            }
        }
    }

    /**
     * Applies the structured output response format to the request builder, if configured.
     */
    public void applyToRequest(ChatRequest.Builder builder) {
        if (config != null && config.isEnabled()) {
            var jsonSchema = config.effectiveJsonSchema();
            if (jsonSchema != null) {
                builder.responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(jsonSchema)
                        .build());
            }
        }
    }

    /**
     * Attempts to parse structured output from the AI response.
     * If parsing fails and no force attempt has been made yet, adds the force prompt
     * to the message list and returns {@code true} to signal the loop should continue.
     */
    public boolean handleResponse(AiMessage aiMessage, List<ChatMessage> messages) {
        if (config == null || !config.isEnabled()) {
            return false;
        }
        if (aiMessage.hasToolExecutionRequests()) {
            return false;
        }
        var responseText = aiMessage.text() != null ? aiMessage.text() : "";
        try {
            OBJECT_MAPPER.readTree(responseText);
            result = responseText;
        } catch (JsonProcessingException e) {
            if (!forceAttempted) {
                forceAttempted = true;
                messages.add(UserMessage.from(config.forcePrompt()));
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the parsed structured output result, or null if not available.
     */
    public String getResult() {
        return result;
    }

    /**
     * Resets the state for a new execution.
     */
    public void reset() {
        this.result = null;
        this.forceAttempted = false;
    }
}
