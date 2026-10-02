package de.augmentia.quad.core.tool;

import java.util.Set;

/**
 * Tool metadata, materialised once at registration time.
 *
 * <p>Replaces reflection on the tool method at execution time. The previous approach read
 * {@code @Idempotent} off the {@code spec()} method of the {@link ToolMethod} implementation,
 * which never carries the annotation for reflective tools — so the idempotency cache was
 * effectively dead.
 *
 * @param name           tool name as sent to the model
 * @param description    human/LLM-readable description; must be non-blank
 * @param idempotent     whether repeated identical calls may be served from cache
 * @param hidden         excluded from the model's view of the catalog
 * @param roles          roles allowed to invoke the tool; empty means "no restriction"
 * @param generationMethod optional provenance marker (from {@code @GenerationMethod})
 * @param securityContext optional per-tool security requirement, or null
 */
public record ToolMeta(
        String name,
        String description,
        boolean idempotent,
        boolean hidden,
        Set<String> roles,
        String generationMethod,
        Object securityContext) {

    public ToolMeta {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /** Metadata for a tool that is always visible and has no extra constraints. */
    public static ToolMeta simple(String name, String description) {
        return new ToolMeta(name, description, false, false, Set.of(), null, null);
    }

    /** Returns a copy with {@code description} replaced, for fill-in of absent values. */
    public ToolMeta withDescription(String newDescription) {
        return new ToolMeta(name, newDescription, idempotent, hidden, roles,
                generationMethod, securityContext);
    }

    public ToolMeta withIdempotent(boolean value) {
        return new ToolMeta(name, description, value, hidden, roles, generationMethod, securityContext);
    }
}