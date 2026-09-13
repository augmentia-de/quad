package de.augmentia.quad.core.config;


import static de.augmentia.quad.core.config.ConfigReader.*;


/**
 * Flat LLM configuration (deprecated — use ChatModelConfig with ChatRequestParameters instead).
 */
public record LlmConfig(
    String apiKey,
    String baseUrl,
    String modelName,
    Integer maxRetries,
    Boolean logRequests,
    Boolean logResponses
) {

    public static LlmConfig fromEnv() {
        return new LlmConfig(
            get("OPENAI_API_KEY"),
            get("OPENAI_BASE_URL"),
            get("OPENAI_MODEL"),
            parseInt(get("LLM_MAX_RETRIES")),
            parseBoolean(get("LLM_LOG_REQUESTS")),
            parseBoolean(get("LLM_LOG_RESPONSES"))
        );
    }

    public static LlmConfig fromEnv(String modelName) {
        var env = fromEnv();
        return new LlmConfig(
            env.apiKey(),
            env.baseUrl(),
            modelName != null && !modelName.isBlank() ? modelName : env.modelName(),
            env.maxRetries(),
            env.logRequests(),
            env.logResponses()
        );
    }

}
