package de.augmentia.quad.core.hook.pipeline;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Container for typed context records passed to hook lifecycle methods.
 * Types are adapted to QUAD's use of LangChain4j {@link ChatMessage} and
 * {@link ToolSpecification} directly (no separate Message wrapper).
 */
public final class HookContexts {

    private HookContexts() {}

    public record BeforeAgentContext(
        String sessionId,
        String prompt,
        Map<String, Object> contextVariables
    ) {}

    public record AfterAgentContext(
        String sessionId,
        String result
    ) {}

    public record BeforeModelCallContext(
        String sessionId,
        StringBuilder systemPrompt,
        List<ChatMessage> messages,
        List<ToolSpecification> tools,
        List<ChatMessage> additionalMessages
    ) {}

    public record AfterModelCallContext(
        String sessionId,
        String llmResponse,
        int inputTokens,
        int outputTokens
    ) {}

    public record BeforeToolCallContext(
        String sessionId,
        String toolName,
        Map<String, Object> arguments
    ) {}

    public record AfterToolCallContext(
        String sessionId,
        String toolName,
        String result,
        boolean isError,
        List<ChatMessage> additionalMessages
    ) {
        public AfterToolCallContext(String sessionId, String toolName, String result, boolean isError) {
            this(sessionId, toolName, result, isError, new ArrayList<>());
        }
    }
}
