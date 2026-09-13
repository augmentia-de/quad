package de.augmentia.quad.core.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Factory for creating LangChain4j ChatModel and StreamingChatModel instances. */
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);
    private static final Map<ModelProviderType, ModelProvider> providers = new ConcurrentHashMap<>();

    static {
        register(ModelProviderType.OPENAI, new OpenAiProvider());
        register(ModelProviderType.OPENAI_COMPATIBLE, new OpenAiCompatibleProvider());
    }

    /** Registers a ModelProvider for a given provider type. */
    public static void register(ModelProviderType type, ModelProvider provider) {
        providers.put(type, provider);
    }

    // ── Tier-based API (new) ──

    /** Creates a ChatModel for the given tier using the tiered configuration. */
    public static ChatModel createChatModel(ModelTier tier, TieredModelConfig tieredConfig) {
        var config = tieredConfig.forTier(tier);
        return provider(config.provider()).createChatModel(config);
    }

    /** Creates a StreamingChatModel for the given tier; falls back to a sync-to-streaming bridge. */
    public static StreamingChatModel createStreamingChatModel(ModelTier tier, TieredModelConfig tieredConfig) {
        var config = tieredConfig.forTier(tier);
        var streamingProvider = provider(config.provider());
        var result = streamingProvider.createStreamingChatModel(config);
        return result;
    }

    /** Creates a ChatModel from a full ChatModelConfig. */
    public static ChatModel createChatModel(ChatModelConfig config) {
        return provider(config.provider()).createChatModel(config);
    }

    // ── BC: Old OpenAI-specific API ──

    /** @deprecated Use tier-based API. Creates an OpenAI ChatModel from env with optional API key override. */
    public static ChatModel createOpenAiFromEnv(String api_key) {
        LlmConfig config = LlmConfig.fromEnv();
        if (api_key != null) config = new LlmConfig(api_key, config.baseUrl(), config.modelName(), config.maxRetries(), config.logRequests(), config.logResponses());
        log.info("createOpenAiFromEnv: apiKey={} baseUrl={} model={}",
            ConfigReader.mask(config.apiKey()), config.baseUrl(), config.modelName());
        return createOpenAi(config);
    }

    /** @deprecated Use tier-based API. Creates an OpenAI ChatModel from environment variables. */
    public static ChatModel createOpenAiFromEnv() {
        LlmConfig config = LlmConfig.fromEnv();
        log.info("createOpenAiFromEnv (no arg): apiKey={} baseUrl={} model={}",
            ConfigReader.mask(config.apiKey()), config.baseUrl(), config.modelName());
        return createOpenAi(config);
    }

    /** @deprecated Use tier-based API. Creates an OpenAI ChatModel from a flat LlmConfig. */
    public static ChatModel createOpenAi(LlmConfig config) {
        log.info("createOpenAi: apiKey={} baseUrl={} model={}",
            ConfigReader.mask(config.apiKey()), config.baseUrl(), config.modelName());
        var defaults = dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder().build();
        var c = new ChatModelConfig(
            ModelProviderType.OPENAI,
            config.apiKey(),
            config.baseUrl(),
            config.modelName(),
            config.maxRetries(),
            Map.of(),
            config.logRequests(),
            config.logResponses(),
            defaults
        );
        return provider(ModelProviderType.OPENAI).createChatModel(c);
    }

    /** @deprecated Use tier-based API. Creates an OpenAI StreamingChatModel from env with optional API key override. */
    public static StreamingChatModel createOpenAiStreamingFromEnv(String api_key) {
        LlmConfig config = LlmConfig.fromEnv();
        if (api_key != null) config = new LlmConfig(api_key, config.baseUrl(), config.modelName(), config.maxRetries(), config.logRequests(), config.logResponses());
        return createOpenAiStreaming(config);
    }

    /** @deprecated Use tier-based API. Creates an OpenAI StreamingChatModel from a flat LlmConfig. */
    public static StreamingChatModel createOpenAiStreaming(LlmConfig config) {
        var defaults = dev.langchain4j.model.chat.request.DefaultChatRequestParameters.builder().build();
        var c = new ChatModelConfig(
            ModelProviderType.OPENAI,
            config.apiKey(),
            config.baseUrl(),
            config.modelName(),
            config.maxRetries(),
            Map.of(),
            config.logRequests(),
            config.logResponses(),
            defaults
        );
        return provider(ModelProviderType.OPENAI).createStreamingChatModel(c);
    }

    /** Looks up the registered ModelProvider for the given type. */
    private static ModelProvider provider(ModelProviderType type) {
        var p = providers.get(type);
        if (p == null) throw new IllegalStateException("No ModelProvider registered for " + type);
        return p;
    }

    // ── Provider Implementations ──

    /** ModelProvider for OpenAI. */
    static class OpenAiProvider implements ModelProvider {
        @Override
        public ChatModel createChatModel(ChatModelConfig config) {
            log.info("OpenAiProvider.createChatModel: apiKey={} baseUrl={} model={} temp={} maxOutput={}",
                config.apiKey() != null ? ConfigReader.mask(config.apiKey()) : null,
                config.baseUrl(), config.modelName(),
                config.defaults() != null ? config.defaults().temperature() : null,
                config.defaults() != null ? config.defaults().maxOutputTokens() : null);
            var builder = OpenAiChatModel.builder();
            if (config.apiKey() != null) builder.apiKey(config.apiKey());
            if (config.baseUrl() != null && !config.baseUrl().isBlank()) builder.baseUrl(config.baseUrl());
            builder.modelName(config.modelName() != null && !config.modelName().isBlank()
                ? config.modelName() : "gpt-4o-mini");
            if (config.defaults() != null) {
                if (config.defaults().temperature() != null) builder.temperature(config.defaults().temperature());
                if (config.defaults().maxOutputTokens() != null) builder.maxTokens(config.defaults().maxOutputTokens());
            }
            builder.maxRetries(0);
            if (config.logRequests() != null) builder.logRequests(config.logRequests());
            if (config.logResponses() != null) builder.logResponses(config.logResponses());
            return RetryingChatModel.withDefaults(builder.build());
        }

        @Override
        public StreamingChatModel createStreamingChatModel(ChatModelConfig config) {
            log.info("OpenAiProvider.createStreamingChatModel: apiKey={} baseUrl={} model={}",
                config.apiKey() != null ? ConfigReader.mask(config.apiKey()) : null,
                config.baseUrl(), config.modelName());
            var builder = OpenAiStreamingChatModel.builder();
            if (config.apiKey() != null) builder.apiKey(config.apiKey());
            if (config.baseUrl() != null && !config.baseUrl().isBlank()) builder.baseUrl(config.baseUrl());
            builder.modelName(config.modelName() != null && !config.modelName().isBlank()
                ? config.modelName() : "gpt-4o");
            if (config.defaults() != null) {
                if (config.defaults().temperature() != null) builder.temperature(config.defaults().temperature());
                if (config.defaults().maxOutputTokens() != null) builder.maxTokens(config.defaults().maxOutputTokens());
            }
            if (config.logRequests() != null) builder.logRequests(config.logRequests());
            if (config.logResponses() != null) builder.logResponses(config.logResponses());
            return builder.build();
        }
    }

    /** ModelProvider for OpenAI-compatible APIs. */
    static class OpenAiCompatibleProvider implements ModelProvider {
        @Override
        public ChatModel createChatModel(ChatModelConfig config) {
            var builder = OpenAiChatModel.builder();
            if (config.apiKey() != null) builder.apiKey(config.apiKey());
            var baseUrl = config.baseUrl() != null && !config.baseUrl().isBlank()
                ? config.baseUrl() : "http://localhost:8080/v1";
            builder.baseUrl(baseUrl);
            builder.modelName(config.modelName() != null && !config.modelName().isBlank()
                ? config.modelName() : "default");
            if (config.defaults() != null) {
                if (config.defaults().temperature() != null) builder.temperature(config.defaults().temperature());
                if (config.defaults().maxOutputTokens() != null) builder.maxTokens(config.defaults().maxOutputTokens());
            }
            builder.maxRetries(0);
            if (config.logRequests() != null) builder.logRequests(config.logRequests());
            if (config.logResponses() != null) builder.logResponses(config.logResponses());
            return RetryingChatModel.withDefaults(builder.build());
        }

        @Override
        public StreamingChatModel createStreamingChatModel(ChatModelConfig config) {
            var builder = OpenAiStreamingChatModel.builder();
            if (config.apiKey() != null) builder.apiKey(config.apiKey());
            var baseUrl = config.baseUrl() != null && !config.baseUrl().isBlank()
                ? config.baseUrl() : "http://localhost:8080/v1";
            builder.baseUrl(baseUrl);
            builder.modelName(config.modelName() != null && !config.modelName().isBlank()
                ? config.modelName() : "default");
            if (config.defaults() != null) {
                if (config.defaults().temperature() != null) builder.temperature(config.defaults().temperature());
            }
            if (config.logRequests() != null) builder.logRequests(config.logRequests());
            if (config.logResponses() != null) builder.logResponses(config.logResponses());
            return builder.build();
        }
    }

}
