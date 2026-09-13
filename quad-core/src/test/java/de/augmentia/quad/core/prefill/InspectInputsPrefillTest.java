package de.augmentia.quad.core.prefill;

import de.augmentia.quad.core.tool.prefill.InspectInputsPrefill;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InspectInputsPrefillTest {

    @Test
    void shouldCreatePrefilledMessages() {
        List<ChatMessage> messages = InspectInputsPrefill.prefill("task", "start:");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) messages.get(1)).text()).isEqualTo("start:");
    }

    @Test
    void shouldEstimateTokensFromMessageTexts() {
        List<ChatMessage> messages = List.of(
                SystemMessage.from("abcdefgh"),   // 8 chars -> 2 tokens
                UserMessage.from("abcdefghijkl")  // 12 chars -> 3 tokens
        );

        assertThat(InspectInputsPrefill.estimateTokens(messages)).isEqualTo(5);
    }

    @Test
    void shouldHandleToolMessagesAndNull() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("1").name("t").arguments("{}").build();
        List<ChatMessage> messages = List.of(
                AiMessage.from(List.of(req)),
                new dev.langchain4j.data.message.ToolExecutionResultMessage("1", "t", "abcdefgh"));

        assertThat(InspectInputsPrefill.estimateTokens(messages)).isEqualTo(2);
        assertThat(InspectInputsPrefill.estimateTokens(null)).isZero();
    }
}
