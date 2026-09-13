package de.augmentia.quad.core.config;

/** Model capability tiers for routing between simple and advanced models. */
public enum ModelTier {
    SIMPLE,
    ADVANCED,
    ROUTING;

    /** Parses a string to a ModelTier, defaulting to SIMPLE. */
    public static ModelTier fromString(String s) {
        if (s == null || s.isBlank()) return SIMPLE;
        return switch (s.trim().toLowerCase()) {
            case "advanced" -> ADVANCED;
            case "routing" -> ROUTING;
            default -> SIMPLE;
        };
    }
}
