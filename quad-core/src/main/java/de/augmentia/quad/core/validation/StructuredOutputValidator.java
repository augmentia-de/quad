package de.augmentia.quad.core.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Structured output with validation and retry. The LLM is instructed to
 * provide JSON conforming to the target type; invalid responses are
 * re-requested via a correction prompt.
 * <p>
 * Source: Python validation via Pydantic models
 */
public final class StructuredOutputValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructuredOutputValidator() {
    }

    public static <T> T extractAndValidate(ChatModel model, String prompt, Class<T> clazz, int maxRetries) {
        if (maxRetries <= 0) {
            throw new IllegalArgumentException("maxRetries must be > 0");
        }
        String currentPrompt = prompt + "\nOutput the result as JSON that conforms to " +
                clazz.getSimpleName() + ".";

        String lastRaw = null;
        Exception lastError = null;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            lastRaw = model.chat(currentPrompt);
            try {
                return MAPPER.readValue(cleanJson(lastRaw), clazz);
            } catch (Exception e) {
                lastError = e;
                currentPrompt = "Invalid JSON (" + e.getMessage() + "). Correct:\n" + lastRaw;
            }
        }
        throw new IllegalArgumentException(
                "Could not produce a valid " + clazz.getSimpleName(), lastError);
    }

    private static String cleanJson(String raw) {
        String clean = raw == null ? "" : raw.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        return clean;
    }
}
