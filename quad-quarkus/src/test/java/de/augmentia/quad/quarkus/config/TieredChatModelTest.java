package de.augmentia.quad.quarkus.config;

import de.augmentia.quad.core.config.ChatModelConfig;
import de.augmentia.quad.core.config.ModelProviderType;
import de.augmentia.quad.core.config.ModelTier;
import de.augmentia.quad.core.config.TieredModelConfig;
import de.augmentia.quad.quarkus.agent.config.TieredChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TieredChatModelTest {

    private static TieredModelConfig config(ModelTier defaultTier) {
        var simple = new ChatModelConfig(ModelProviderType.OPENAI, "k", null,
            "model-simple", null, Map.of(), null, null, null);
        var advanced = new ChatModelConfig(ModelProviderType.OPENAI, "k", null,
            "model-advanced", null, Map.of(), null, null, null);
        return new TieredModelConfig(simple, advanced, defaultTier);
    }

    private static ChatModel ok(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from(text))
                    .build();
            }
        };
    }

    private static ChatModel failing() {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("primary tier unavailable");
            }
        };
    }

    @Test
    void with_default_advanced_primary_is_advanced() {
        var tiered = new TieredChatModel(config(ModelTier.ADVANCED), t -> ok(t.name()));
        assertEquals(ModelTier.ADVANCED, tiered.primaryTier());
        assertEquals(ModelTier.SIMPLE, tiered.fallbackTier());
    }

    @Test
    void with_default_simple_primary_is_simple() {
        var tiered = new TieredChatModel(config(ModelTier.SIMPLE), t -> ok(t.name()));
        assertEquals(ModelTier.SIMPLE, tiered.primaryTier());
        assertEquals(ModelTier.ADVANCED, tiered.fallbackTier());
    }

    @Test
    void routing_defaults_to_simple_primary() {
        var tiered = new TieredChatModel(config(ModelTier.ROUTING), t -> ok(t.name()));
        assertEquals(ModelTier.SIMPLE, tiered.primaryTier());
    }

    @Test
    void falls_back_to_simple_when_advanced_fails() {
        var tiered = new TieredChatModel(config(ModelTier.ADVANCED), t ->
            t == ModelTier.ADVANCED ? failing() : ok("fallback-antwort"));

        ChatResponse response = tiered.chat(ChatRequest.builder()
            .messages(List.of(dev.langchain4j.data.message.UserMessage.from("test")))
            .build());

        assertEquals("fallback-antwort", response.aiMessage().text());
    }
}