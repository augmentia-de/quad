package de.augmentia.quad.core.capability.prompt;

public final class PromptRegistry {

    private static volatile PromptRegistry defaultInstance = new PromptRegistry(new YamlPromptManager("prompts.yaml"));

    private final PromptManager promptManager;

    public PromptRegistry(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    public static PromptRegistry defaultInstance() {
        return defaultInstance;
    }

    public static void setDefaultInstance(PromptRegistry instance) {
        defaultInstance = instance;
    }

    // ── Instance methods ──

    public String getValue(String key, Object... args) {
        return promptManager.get(key, args);
    }

    public String getValueOrDefault(String key, String fallback, Object... args) {
        return promptManager.getOrDefault(key, fallback, args);
    }

    public PromptManager getPromptManager() {
        return promptManager;
    }

    // ── Static convenience methods (delegate to default instance) ──

    public static PromptManager instance() {
        return defaultInstance.promptManager;
    }

    public static void configure(PromptManager pm) {
        if (pm == null) {
            defaultInstance = new PromptRegistry(new YamlPromptManager("prompts.yaml"));
        } else {
            defaultInstance = new PromptRegistry(pm);
        }
    }

    public static String get(String key, Object... args) {
        return defaultInstance.getValue(key, args);
    }

    public static String getOrDefault(String key, String fallback, Object... args) {
        return defaultInstance.getValueOrDefault(key, fallback, args);
    }
}
