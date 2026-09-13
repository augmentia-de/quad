package de.augmentia.quad.core.strategies;

import de.augmentia.quad.core.agent.strategies.PredictStrategy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PredictStrategyTest {

    @Test
    void shouldSendSystemAndUserMessages() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("result")).build());

        String result = new PredictStrategy(model).execute("sys", "user");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        List<ChatMessage> messages = captor.getValue().messages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(result).isEqualTo("result");
    }

    @Test
    void shouldParseValidJsonOutput() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("{\"name\":\"Ada\"}")).build());

        record Person(String name) {}

        Person person = new PredictStrategy(model).executeWithValidation("sys", "user", Person.class);

        assertThat(person.name()).isEqualTo("Ada");
    }

    @Test
    void shouldStripCodeFencesBeforeParsing() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("```json\n{\"name\":\"Bob\"}\n```")).build());

        record Person(String name) {}

        Person person = new PredictStrategy(model).executeWithValidation("sys", "user", Person.class);

        assertThat(person.name()).isEqualTo("Bob");
    }

    @Test
    void shouldFailWhenOutputCannotBeParsed() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("not json")).build());

        record Person(String name) {}

        assertThatThrownBy(() -> new PredictStrategy(model)
                .executeWithValidation("sys", "user", Person.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not parse response as Person");
    }
}
