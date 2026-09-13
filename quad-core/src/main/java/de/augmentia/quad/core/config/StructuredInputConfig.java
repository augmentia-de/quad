package de.augmentia.quad.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.input.PromptTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for structured prompt inputs.
 * <p>
 * Renders a typed, reusable prompt template via LangChain4j's
 * {@link PromptTemplate} (variables as {@code {{name}}}). Values that are
 * Records, POJOs, Maps, Lists or non-primitive objects are serialized to JSON
 * automatically so agents can receive results from previous workflow steps
 * without manual string concatenation.
 * <p>
 * Templates can either be inline strings
 * ({@link #fromTemplate(String)}) or loaded from the classpath
 * ({@link #fromResource(String)}).
 */
public record StructuredInputConfig(
    String template,
    String resourcePath
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static StructuredInputConfig fromTemplate(String template) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("template must not be blank");
        }
        return new StructuredInputConfig(template, null);
    }

    public static StructuredInputConfig fromResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        return new StructuredInputConfig(null, resourcePath);
    }

    /** Returns the effective template, loading the classpath resource if configured. */
    public String effectiveTemplate() {
        if (resourcePath == null) return template;
        return loadFromResource(resourcePath);
    }

    /**
     * Renders the template with the given values.
     * Records/POJOs are serialized to JSON strings; {@code null} becomes an empty string.
     */
    public String render(Map<String, Object> values) {
        var effectiveTemplate = effectiveTemplate();
        Map<String, Object> processed = new LinkedHashMap<>();
        if (values != null) {
            for (var entry : values.entrySet()) {
                processed.put(entry.getKey(), serializeValue(entry.getValue()));
            }
        }
        try {
            return PromptTemplate.from(effectiveTemplate).apply(processed).text();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to render prompt template with values " + processed.keySet(), e);
        }
    }

    private String serializeValue(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Character || value instanceof Enum<?>) return value.toString();
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private String loadFromResource(String path) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = StructuredInputConfig.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Prompt template not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt template: " + path, e);
        }
    }
}