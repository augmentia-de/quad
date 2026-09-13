package de.augmentia.quad.core.config;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelFactoryRetryTest {

    @Test
    void shouldWrapCreatedModelsWithRetryingChatModel() {
        System.setProperty("OPENAI_API_KEY", "test-key-123");

        ChatModel model = ModelFactory.createOpenAiFromEnv();

        assertThat(model).isInstanceOf(RetryingChatModel.class);
        RetryingChatModel retrying = (RetryingChatModel) model;
        assertThat(retrying.delegate()).isNotNull();
    }
}
