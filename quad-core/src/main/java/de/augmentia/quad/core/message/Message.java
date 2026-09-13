package de.augmentia.quad.core.message;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonSerialize(using = MessageSerializer.class)
@JsonDeserialize(using = MessageDeserializer.class)
public record Message(
    String id,
    Instant timestamp,
    ChatMessage delegate,
    Map<String, Object> metadata
) {
    public String content() {
        return switch (delegate) {
            case dev.langchain4j.data.message.UserMessage m -> m.singleText();
            case AiMessage m -> m.text();
            case dev.langchain4j.data.message.SystemMessage m -> m.text();
            case ToolExecutionResultMessage m -> m.text();
            default -> null;
        };
    }

    public boolean isSystem() {
        return delegate instanceof dev.langchain4j.data.message.SystemMessage;
    }

    public boolean isUser() {
        return delegate instanceof dev.langchain4j.data.message.UserMessage;
    }

    public boolean isAssistant() {
        return delegate instanceof AiMessage;
    }

    public boolean isToolResult() {
        return delegate instanceof ToolExecutionResultMessage;
    }

    public List<ToolExecutionRequest> toolExecutionRequests() {
        if (delegate instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
            return ai.toolExecutionRequests();
        }
        return List.of();
    }

    public boolean hasToolExecutionRequests() {
        return delegate instanceof AiMessage ai && ai.hasToolExecutionRequests();
    }

    public String toolName() {
        if (delegate instanceof ToolExecutionResultMessage t) return t.toolName();
        return null;
    }

    public String toolCallId() {
        if (delegate instanceof ToolExecutionResultMessage t) return t.id();
        return null;
    }

    public ChatMessage toChatMessage() {
        return delegate;
    }

    public static Message user(String id, Instant timestamp, String text, Map<String, Object> metadata) {
        var userMsg = text != null
            ? dev.langchain4j.data.message.UserMessage.from(text)
            : new dev.langchain4j.data.message.UserMessage("");
        return new Message(id, timestamp, userMsg, metadata);
    }

    public static Message user(String text) {
        return user(UUID.randomUUID().toString(), Instant.now(), text, Map.of());
    }

    public static Message system(String id, Instant timestamp, String text, Map<String, Object> metadata) {
        return new Message(id, timestamp, new dev.langchain4j.data.message.SystemMessage(text), metadata);
    }

    public static Message system(String text) {
        return system(UUID.randomUUID().toString(), Instant.now(), text, Map.of());
    }

    public static Message assistant(String id, Instant timestamp, String text,
            Map<String, Object> metadata, List<ToolExecutionRequest> tools) {
        var ai = tools != null && !tools.isEmpty()
            ? AiMessage.from(text, tools)
            : new AiMessage(text);
        return new Message(id, timestamp, ai, metadata);
    }

    public static Message assistant(String text) {
        return assistant(UUID.randomUUID().toString(), Instant.now(), text, Map.of(), List.of());
    }

    public static Message assistant(String text, List<ToolExecutionRequest> tools) {
        return assistant(UUID.randomUUID().toString(), Instant.now(), text, Map.of(), tools);
    }

    public static Message toolResult(String id, Instant timestamp, String text,
            Map<String, Object> metadata, String toolCallId, String toolName) {
        var request = ToolExecutionRequest.builder()
            .id(toolCallId)
            .name(toolName)
            .build();
        return new Message(id, timestamp, ToolExecutionResultMessage.from(request, text), metadata);
    }

    public static Message toolResult(String text, String toolCallId, String toolName) {
        return toolResult(UUID.randomUUID().toString(), Instant.now(), text,
            Map.of(), toolCallId, toolName);
    }

    public static Message from(ChatMessage cm) {
        return new Message(UUID.randomUUID().toString(), Instant.now(), cm, Map.of());
    }
}
