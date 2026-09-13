package de.augmentia.quad.core.strategies;

import de.augmentia.quad.core.agent.strategies.TemplateStrategy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateStrategyTest {

    @Test
    void shouldSubstituteAllVariables() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        TemplateStrategy strategy = new TemplateStrategy(model);
        String rendered = strategy.render("Hello {{name}}, you are {{age}}.", Map.of("name", "Ada", "age", "36"));

        assertThat(rendered).isEqualTo("Hello Ada, you are 36.");
    }

    @Test
    void shouldSendRenderedPromptToModel() {
        ChatModel model = mock(ChatModel.class);
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(model.chat(captor.capture())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        new TemplateStrategy(model).execute("Answer {{q}}?", Map.of("q", "42"));

        ChatRequest request = captor.getValue();
        ChatMessage message = request.messages().get(0);
        assertThat(message).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) message).singleText()).isEqualTo("Answer 42?");
    }

    @Test
    void shouldLeaveUnknownVariablesUntouched() {
        TemplateStrategy strategy = new TemplateStrategy(mock(ChatModel.class));
        assertThat(strategy.render("Value: {{missing}}", Map.of())).isEqualTo("Value: {{missing}}");
    }
}
