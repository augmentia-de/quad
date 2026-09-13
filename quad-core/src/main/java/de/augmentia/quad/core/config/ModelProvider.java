package de.augmentia.quad.core.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

public interface ModelProvider {
    ChatModel createChatModel(ChatModelConfig config);

    default StreamingChatModel createStreamingChatModel(ChatModelConfig config) {
        return null;
    }
}
