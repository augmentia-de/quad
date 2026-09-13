package de.augmentia.quad.core.capability.prompt;

public interface PromptManager {

    String get(String key, Object... args);

    default String getOrDefault(String key, String fallback, Object... args) {
        String value = get(key, args);
        return value != null ? value : (args.length > 0 ? String.format(fallback, args) : fallback);
    }
}
