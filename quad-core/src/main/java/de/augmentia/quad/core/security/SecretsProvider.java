package de.augmentia.quad.core.security;

import java.util.Map;
import java.util.Optional;

/** SPI for accessing vaulted secrets. Implementations can be DB-backed, env-based, file-based, etc. */
public interface SecretsProvider {

    /** Returns decrypted value for the given key, or empty if not found. */
    Optional<String> get(String key);

    /** Stores an encrypted secret value. */
    void put(String key, String value);

    /** Whether this provider is enabled/available. */
    boolean isEnabled();

    /** Masks a string for safe logging (show last 3 chars, mask rest with *). */
    static String mask(String s) {
        if (s == null) return null;
        if (s.length() <= 3) return "*****";
        return "*".repeat(Math.max(0, s.length() - 3)) + s.substring(s.length() - 3);
    }

    /** Checks if a value looks sensitive enough to warrant masking. */
    static boolean looksSensitive(String s) {
        if (s == null || s.isBlank()) return false;
        String lower = s.toLowerCase();
        return lower.contains("key") || lower.contains("token") || lower.contains("password")
            || lower.contains("secret") || lower.contains("credential");
    }
}
