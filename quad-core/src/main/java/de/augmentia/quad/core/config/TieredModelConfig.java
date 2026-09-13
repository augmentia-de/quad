package de.augmentia.quad.core.config;

import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static de.augmentia.quad.core.config.ConfigReader.*;

/**
 * Holds two tiers of chat model configuration (simple/advanced) and a default tier selector.
 */
public record TieredModelConfig(
    ChatModelConfig simple,
    ChatModelConfig advanced,
    ModelTier defaultTier
) {

    private static final Logger log = LoggerFactory.getLogger(TieredModelConfig.class);

    /** Builds a TieredModelConfig from environment variables. */
    public static TieredModelConfig fromEnv() {
        var openaiApiKey = get("OPENAI_API_KEY", null);
        log.info("TieredModelConfig.fromEnv: OPENAI_API_KEY={}", ConfigReader.mask(openaiApiKey));
        // Global fallback: LLM_* and OPENAI_* env vars
        var defaultsBuilder = DefaultChatRequestParameters.builder();
        var temp = parseDouble(get("LLM_TEMPERATURE", null));
        if (temp != null) defaultsBuilder.temperature(temp);
        var maxTokens = parseInt(get("OPENAI_MAX_TOKENS", get("LLM_MAX_TOKENS", null)));
        if (maxTokens != null) defaultsBuilder.maxOutputTokens(maxTokens);
        var globalFallback = new ChatModelConfig(
            ModelProviderType.OPENAI,
            get("OPENAI_API_KEY", null),
            get("OPENAI_BASE_URL", null),
            get("OPENAI_MODEL", null),
            parseInt(get("LLM_MAX_RETRIES", null)),
            Map.of(),
            parseBoolean(get("LLM_LOG_REQUESTS", null)),
            parseBoolean(get("LLM_LOG_RESPONSES", null)),
            defaultsBuilder.build()
        );

        var simple = ChatModelConfig.fromEnvWithFallback("SIMPLE_", globalFallback);
        var advanced = ChatModelConfig.fromEnvWithFallback("ADVANCED_", null);

        log.info("TieredModelConfig: simple apiKey={} model={} | advanced apiKey={} model={}",
            ConfigReader.mask(simple.apiKey()), simple.modelName(),
            ConfigReader.mask(advanced != null ? advanced.apiKey() : null),
            advanced != null ? advanced.modelName() : null);

        // Fallback: if ADVANCED has no config at all, derive from simple but with a different model name
        if (advanced == null || !hasAny("ADVANCED_")) {
            advanced = new ChatModelConfig(
                simple.provider(),
                simple.apiKey(),
                simple.baseUrl(),
                get("ADVANCED_MODEL", get("OPENAI_MODEL", "gpt-4o")),
                simple.maxRetries(),
                simple.providerProperties(),
                simple.logRequests(),
                simple.logResponses(),
                simple.defaults()
            );
            log.info("TieredModelConfig: advanced derived from simple, model={}", advanced.modelName());
        }

        var defaultTier = ModelTier.fromString(get("LLM_DEFAULT_TIER", "simple"));

        log.info("TieredModelConfig: final simple.apiKey={} advanced.apiKey={}",
            ConfigReader.mask(simple.apiKey()), ConfigReader.mask(advanced.apiKey()));
        return new TieredModelConfig(simple, advanced, defaultTier);
    }

    /** Returns the ChatModelConfig for the given tier. */
    public ChatModelConfig forTier(ModelTier tier) {
        return switch (tier) {
            case SIMPLE -> simple;
            case ADVANCED -> advanced;
            case ROUTING -> simple; // routing uses simple for initial analysis
        };
    }
}
