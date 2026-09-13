package de.augmentia.quad.quarkus.agent.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Statically configured user-granted directories, bound to
 * {@code quad.workspace.granted-dirs}.
 * <p>
 * Format: comma-separated paths; each entry may be suffixed with
 * {@code :rw} to allow writing (default is read-only).
 * Example: {@code /data/project, /data/shared:rw}
 */
@RegisterForReflection
@ApplicationScoped
public class GrantedDirsConfig {

    @ConfigProperty(name = "quad.workspace.granted-dirs")
    Optional<String> grantedDirs;

    /** Map of normalized absolute path -> writable flag. */
    public Map<Path, Boolean> directories() {
        if (grantedDirs.isEmpty() || grantedDirs.get().isBlank()) {
            return Collections.emptyMap();
        }
        Map<Path, Boolean> out = new LinkedHashMap<>();
        for (String entry : grantedDirs.get().split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            boolean writable = false;
            if (trimmed.endsWith(":rw")) {
                writable = true;
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            } else if (trimmed.endsWith(":ro")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
            if (trimmed.isEmpty()) continue;
            out.put(Path.of(trimmed).toAbsolutePath().normalize(), writable);
        }
        return out;
    }
}
