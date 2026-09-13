package de.augmentia.quad.core.retry;

import de.augmentia.quad.core.config.RetryingChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetryingChatModelTest {

    private ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    @Test
    void shouldDelegateOnSuccess() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.chat(any(ChatRequest.class))).thenReturn(response("ok"));

        ChatResponse result = new RetryingChatModel(delegate, 3, 5, 2.0)
                .chat(ChatRequest.builder().messages(AiMessage.from("hi")).build());

        assertThat(result.aiMessage().text()).isEqualTo("ok");
    }

    @Test
    void shouldRetryAndSucceed() {
        ChatModel delegate = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        when(delegate.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() < 3) {
                throw new RuntimeException("transient");
            }
            return response("recovered");
        });

        ChatResponse result = new RetryingChatModel(delegate, 3, 1, 2.0)
                .chat(ChatRequest.builder().messages(AiMessage.from("hi")).build());

        assertThat(result.aiMessage().text()).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void shouldThrowAfterExhaustingRetries() {
        ChatModel delegate = mock(ChatModel.class);
        doThrow(new RuntimeException("always fails"))
                .when(delegate).chat(any(ChatRequest.class));

        assertThatThrownBy(() -> new RetryingChatModel(delegate, 2, 1, 2.0)
                .chat(ChatRequest.builder().messages(AiMessage.from("hi")).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retries exhausted after 2 attempts")
                .hasRootCauseMessage("always fails");
    }

    @Test
    void shouldValidateConstructorArguments() {
        ChatModel delegate = mock(ChatModel.class);
        assertThatThrownBy(() -> new RetryingChatModel(null, 1, 1, 2.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delegate");
        assertThatThrownBy(() -> new RetryingChatModel(delegate, -1, 1, 2.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries");
    }
}
