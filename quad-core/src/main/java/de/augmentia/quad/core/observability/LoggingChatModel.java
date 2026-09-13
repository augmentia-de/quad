package de.augmentia.quad.core.observability;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

public class LoggingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final FileLlmLogger logger;

    public LoggingChatModel(ChatModel delegate, FileLlmLogger logger) {
        this.delegate = delegate;
        this.logger = logger;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        long start = System.nanoTime();
        ChatResponse response = delegate.chat(chatRequest);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        if (logger != null) {
            logger.log(chatRequest, response, durationMs);
        }
        return response;
    }

    public ChatModel delegate() {
        return delegate;
    }
}
