package de.augmentia.quad.core.strategies;

import de.augmentia.quad.core.agent.strategies.ReflexionStrategy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReflexionStrategyTest {

    @Test
    void shouldReturnCandidateWhenEvaluatorAccepts() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("good")).build());

        String result = new ReflexionStrategy(model).execute("task", s -> true, 3);

        assertThat(result).isEqualTo("good");
    }

    @Test
    void shouldIterateUntilEvaluatorAccepts() {
        ChatModel model = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            return ChatResponse.builder().aiMessage(AiMessage.from("candidate-" + n)).build();
        });

        String result = new ReflexionStrategy(model).execute("task", s -> s.contains("candidate-3"), 5);

        assertThat(result).isEqualTo("candidate-3");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void shouldAppendReflectionMessagesBetweenAttempts() {
        ChatModel model = mock(ChatModel.class);
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(model.chat(captor.capture())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("bad")).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        new ReflexionStrategy(model).execute("task", s -> s.equals("ok"), 3);

        ChatRequest secondRequest = captor.getAllValues().get(1);
        assertThat(secondRequest.messages()).hasSize(3);
        assertThat(secondRequest.messages().get(2)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) secondRequest.messages().get(2)).singleText()).contains("Reflect");
    }

    @Test
    void shouldThrowWhenMaxReflectionsExceeded() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("never good")).build());

        assertThatThrownBy(() -> new ReflexionStrategy(model).execute("task", s -> false, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed after 2 reflections");
    }
}
