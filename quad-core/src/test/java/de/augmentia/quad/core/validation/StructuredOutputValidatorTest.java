package de.augmentia.quad.core.validation;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructuredOutputValidatorTest {

    record Result(String value, int count) {
    }

    @Test
    void shouldParseValidJson() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"value\":\"ok\",\"count\":2}");

        Result result = StructuredOutputValidator.extractAndValidate(model, "prompt", Result.class, 3);

        assertThat(result.value()).isEqualTo("ok");
        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    void shouldStripCodeFences() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("```json\n{\"value\":\"x\",\"count\":1}\n```");

        Result result = StructuredOutputValidator.extractAndValidate(model, "prompt", Result.class, 3);

        assertThat(result.value()).isEqualTo("x");
    }

    @Test
    void shouldRetryWithCorrectionUntilValid() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString()))
                .thenReturn("not json")
                .thenReturn("{\"value\":\"fixed\",\"count\":7}");

        Result result = StructuredOutputValidator.extractAndValidate(model, "prompt", Result.class, 3);

        assertThat(result.value()).isEqualTo("fixed");
    }

    @Test
    void shouldThrowWhenAllAttemptsFail() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("invalid");

        assertThatThrownBy(() -> StructuredOutputValidator
                .extractAndValidate(model, "prompt", Result.class, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not produce a valid Result");
    }

    @Test
    void shouldRequirePositiveRetries() {
        ChatModel model = mock(ChatModel.class);
        assertThatThrownBy(() -> StructuredOutputValidator
                .extractAndValidate(model, "prompt", Result.class, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries");
    }
}
