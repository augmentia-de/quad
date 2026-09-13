package de.augmentia.quad.quarkus.agent.hook;

import de.augmentia.quad.core.hitl.HITLPlugin;
import de.augmentia.quad.core.hook.plugin.Plugin;
import de.augmentia.quad.quarkus.hitl.HitlService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registry for named lifecycle hooks ({@link Plugin}) that can be attached per agent.
 * Each registered hook name is exposed via {@link #listAll()} so the UI can offer it
 * as a selectable option (like guardrails). Registering a new hook here makes it
 * immediately usable per agent — no further DTO/DB/UI changes needed.
 */
@ApplicationScoped
public class HookRegistry {

    private static final Logger log = Logger.getLogger(HookRegistry.class);

    @Inject
    HitlService hitlService;

    private final Map<String, Supplier<Plugin>> hooks = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        if (hitlService != null && hitlService.checkpointService() != null) {
            register("hitl",
                () -> new HITLPlugin(hitlService.checkpointService()),
                "Human-in-the-loop: Tool-Ausführung erfordert Freigabe (Checkpoint)");
        } else {
            log.warn("HITL-Hook not registered: HitlService/CheckpointService not available");
        }
    }

    /** Registers a hook factory under a name (case-insensitive). */
    public void register(String name, Supplier<Plugin> factory) {
        register(name, factory, null);
    }

    /** Registers a hook factory with an optional description (shown in the UI). */
    public void register(String name, Supplier<Plugin> factory, String description) {
        if (name == null || name.isBlank() || factory == null) return;
        String key = name.strip().toLowerCase();
        hooks.put(key, factory);
        if (description != null && !description.isBlank()) {
            descriptions.put(key, description);
        }
    }

    /** Creates a plugin by name; unknown names are skipped (logged). */
    public Plugin create(String name) {
        if (name == null || name.isBlank()) return null;
        Supplier<Plugin> factory = hooks.get(name.strip().toLowerCase());
        if (factory == null) {
            log.warnf("Unbekannter Hook '%s' — ignoriert", name);
            return null;
        }
        return factory.get();
    }

    /** Maps hook names to plugins; unknown names are skipped. */
    public List<Plugin> fromNames(String[] names) {
        var result = new ArrayList<Plugin>();
        if (names == null) return result;
        for (String name : names) {
            Plugin plugin = create(name);
            if (plugin != null) result.add(plugin);
        }
        return result;
    }

    /** Catalog for the UI: {@code {name, type, active, description}}. */
    public List<Map<String, Object>> listAll() {
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : hooks.entrySet()) {
            var info = new LinkedHashMap<String, Object>();
            info.put("name", entry.getKey());
            info.put("type", "hook");
            info.put("active", true);
            String description = descriptions.get(entry.getKey());
            if (description != null) {
                info.put("description", description);
            }
            result.add(info);
        }
        return result;
    }

    public HitlService hitlService() {
        return hitlService;
    }
}