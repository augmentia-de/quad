package de.augmentia.quad.core.guards;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextOnlyGuardTest {

    @Test
    void shouldAcceptToolCallResponseAndResetCounter() {
        TextOnlyGuard guard = new TextOnlyGuard(3);
        List<ChatMessage> messages = new ArrayList<>();

        guard.checkAndGuard(AiMessage.from("text"), messages, "method");
        assertThat(guard.consecutiveTextOnly()).isEqualTo(1);

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("1").name("t").arguments("{}").build();
        boolean valid = guard.checkAndGuard(AiMessage.from(List.of(req)), messages, "method");

        assertThat(valid).isTrue();
        assertThat(guard.consecutiveTextOnly()).isZero();
    }

    @Test
    void shouldInjectCorrectionForTextOnlyReply() {
        TextOnlyGuard guard = new TextOnlyGuard(3);
        List<ChatMessage> messages = new ArrayList<>();

        boolean valid = guard.checkAndGuard(AiMessage.from("plain text"), messages, "doWork");

        assertThat(valid).isFalse();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).isInstanceOf(dev.langchain4j.data.message.UserMessage.class);
        assertThat(((dev.langchain4j.data.message.UserMessage) messages.get(0)).singleText())
                .contains("DONE");
    }

    @Test
    void shouldThrowAfterMaxTextOnlyReplies() {
        TextOnlyGuard guard = new TextOnlyGuard(2);
        List<ChatMessage> messages = new ArrayList<>();

        guard.checkAndGuard(AiMessage.from("one"), messages, "method");
        assertThatThrownBy(() -> guard.checkAndGuard(AiMessage.from("two"), messages, "method"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plain text 2 times in a row for method");
    }

    @Test
    void shouldResetCounterOnDoneSignal() {
        TextOnlyGuard guard = new TextOnlyGuard(3);
        List<ChatMessage> messages = new ArrayList<>();

        guard.checkAndGuard(AiMessage.from("text"), messages, "method");
        assertThat(guard.consecutiveTextOnly()).isEqualTo(1);

        boolean valid = guard.checkAndGuard(AiMessage.from("DONE: Result here"), messages, "method");
        assertThat(valid).isTrue();
        assertThat(guard.consecutiveTextOnly()).isZero();
    }

    @Test
    void shouldRecognizeVariousDoneSignals() {
        TextOnlyGuard guard = new TextOnlyGuard(3);
        List<ChatMessage> messages = new ArrayList<>();

        for (String signal : List.of("DONE", "FINISHED", "RESULT: test", "COMPLETE")) {
            boolean valid = guard.checkAndGuard(AiMessage.from(signal), messages, "method");
            assertThat(valid).as("Signal: %s", signal).isTrue();
            assertThat(guard.consecutiveTextOnly()).isZero();
        }
    }

    @Test
    void shouldValidateMaxTextOnly() {
        assertThatThrownBy(() -> new TextOnlyGuard(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTextOnly");
    }
}
