package de.augmentia.quad.core.config;

import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;

import java.util.HashMap;
import java.util.Map;

import static de.augmentia.quad.core.config.ConfigReader.*;

/**
 * Configuration record for a single chat model (provider, API key, model name, etc.).
 * Request defaults (temperature, maxOutputTokens, topP, ...) live in {@link ChatRequestParameters}.
 */
public record ChatModelConfig(
    ModelProviderType provider,
    String apiKey,
    String baseUrl,
    String modelName,
    Integer maxRetries,
    Map<String, String> providerProperties,
    Boolean logRequests,
    Boolean logResponses,
    ChatRequestParameters defaults
) {

    /** Normalizes null providerProperties to an empty map. */
    public ChatModelConfig {
        if (providerProperties == null) {
            providerProperties = Map.of();
        }
        if (defaults == null) {
            defaults = DefaultChatRequestParameters.EMPTY;
        }
    }

    /** Reads configuration from environment variables with the given prefix. */
    public static ChatModelConfig fromEnv(String prefix) {
        var provider = ModelProviderType.fromEnv(prefix);
        var props = new HashMap<String, String>();
        var ollamaUrl = get(prefix + "OLLAMA_BASE_URL", null);
        if (ollamaUrl != null) props.put("baseUrl", ollamaUrl);
        var defaults = buildDefaults(prefix, null);
        return new ChatModelConfig(
            provider,
            get(prefix + "API_KEY", null),
            get(prefix + "BASE_URL", null),
            get(prefix + "MODEL", null),
            parseInt(get(prefix + "MAX_RETRIES", null)),
            Map.copyOf(props),
            parseBoolean(get(prefix + "LOG_REQUESTS", null)),
            parseBoolean(get(prefix + "LOG_RESPONSES", null)),
            defaults
        );
    }

    /** Reads from env vars with fallback to default values when no prefixed vars are set. */
    public static ChatModelConfig fromEnvWithFallback(String prefix, ChatModelConfig fallback) {
        var provider = ModelProviderType.fromEnv(prefix);
        if (!hasAny(prefix)) return fallback;
        var props = new HashMap<String, String>();
        var ollamaUrl = get(prefix + "OLLAMA_BASE_URL", fallback != null ? fallback.providerProperties().get("baseUrl") : null);
        if (ollamaUrl != null) props.put("baseUrl", ollamaUrl);
        var defaults = buildDefaults(prefix, fallback != null ? fallback.defaults() : null);
        return new ChatModelConfig(
            provider,
            get(prefix + "API_KEY", fallback != null ? fallback.apiKey() : null),
            get(prefix + "BASE_URL", fallback != null ? fallback.baseUrl() : null),
            get(prefix + "MODEL", fallback != null ? fallback.modelName() : null),
            parseInt(get(prefix + "MAX_RETRIES", fallback != null && fallback.maxRetries() != null ? fallback.maxRetries().toString() : null)),
            Map.copyOf(props),
            parseBoolean(get(prefix + "LOG_REQUESTS", fallback != null && fallback.logRequests() != null ? fallback.logRequests().toString() : null)),
            parseBoolean(get(prefix + "LOG_RESPONSES", fallback != null && fallback.logResponses() != null ? fallback.logResponses().toString() : null)),
            defaults
        );
    }

    /** Merges vault secrets into an existing config, falling back to the provided defaults. */
    public static ChatModelConfig fromVault(Map<String, String> secrets, ChatModelConfig fallback) {
        var props = new HashMap<>(fallback.providerProperties());
        if (secrets.containsKey("ollama_base_url")) {
            props.put("baseUrl", secrets.get("ollama_base_url"));
        }
        var defaultsBuilder = DefaultChatRequestParameters.builder();
        if (fallback.defaults() != null) {
            if (fallback.defaults().temperature() != null) defaultsBuilder.temperature(fallback.defaults().temperature());
            if (fallback.defaults().maxOutputTokens() != null) defaultsBuilder.maxOutputTokens(fallback.defaults().maxOutputTokens());
            if (fallback.defaults().topP() != null) defaultsBuilder.topP(fallback.defaults().topP());
        }
        if (secrets.containsKey("temperature")) defaultsBuilder.temperature(parseDouble(secrets.get("temperature")));
        if (secrets.containsKey("max_tokens")) defaultsBuilder.maxOutputTokens(parseInt(secrets.get("max_tokens")));
        if (secrets.containsKey("top_p")) defaultsBuilder.topP(parseDouble(secrets.get("top_p")));
        return new ChatModelConfig(
            fallback.provider(),
            secrets.getOrDefault("api_key", fallback.apiKey()),
            secrets.getOrDefault("base_url", fallback.baseUrl()),
            secrets.getOrDefault("model", fallback.modelName()),
            parseInt(secrets.get("max_retries")),
            Map.copyOf(props),
            parseBoolean(secrets.get("log_requests")),
            parseBoolean(secrets.get("log_responses")),
            defaultsBuilder.build()
        );
    }

    /** Returns a copy of this config with a different API key. */
    public ChatModelConfig withApiKey(String apiKey) {
        return new ChatModelConfig(provider, apiKey, baseUrl, modelName, maxRetries, providerProperties, logRequests, logResponses, defaults);
    }

    /** Returns a copy of this config with a different model name. */
    public ChatModelConfig withModelName(String modelName) {
        return new ChatModelConfig(provider, apiKey, baseUrl, modelName, maxRetries, providerProperties, logRequests, logResponses, defaults);
    }

    /** Returns a copy of this config with different defaults. */
    public ChatModelConfig withDefaults(ChatRequestParameters defaults) {
        return new ChatModelConfig(provider, apiKey, baseUrl, modelName, maxRetries, providerProperties, logRequests, logResponses, defaults);
    }

    /** Builds ChatRequestParameters from env vars with optional fallback. */
    private static ChatRequestParameters buildDefaults(String prefix, ChatRequestParameters fallback) {
        var builder = DefaultChatRequestParameters.builder();
        if (fallback != null) {
            if (fallback.temperature() != null) builder.temperature(fallback.temperature());
            if (fallback.maxOutputTokens() != null) builder.maxOutputTokens(fallback.maxOutputTokens());
            if (fallback.topP() != null) builder.topP(fallback.topP());
            if (fallback.topK() != null) builder.topK(fallback.topK());
        }
        var temp = parseDouble(get(prefix + "TEMPERATURE", null));
        if (temp != null) builder.temperature(temp);
        var maxTokens = parseInt(get(prefix + "MAX_TOKENS", null));
        if (maxTokens != null) builder.maxOutputTokens(maxTokens);
        var topP = parseDouble(get(prefix + "TOP_P", null));
        if (topP != null) builder.topP(topP);
        var topK = parseInt(get(prefix + "TOP_K", null));
        if (topK != null) builder.topK(topK);
        return builder.build();
    }
}
