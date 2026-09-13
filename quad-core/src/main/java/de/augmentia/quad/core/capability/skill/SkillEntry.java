package de.augmentia.quad.core.capability.skill;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A persisted skill's data (DB-backed skillstore). Separate from the file-based
 * {@link Skill}/{@link TextSkill} model; carries the tool-filtering logic used when
 * a DB skill is applied to an agent's tool registry.
 */
public record SkillEntry(
    String id,
    String name,
    String description,
    String instructions,
    List<String> allowedTools,
    List<String> declaredTools,
    Map<String, String> meta,
    Instant createdAt
) {

    public SkillEntry {
        if (allowedTools == null) allowedTools = List.of();
        if (declaredTools == null) declaredTools = List.of();
        if (meta == null) meta = Map.of();
    }

    /** Filters a set of tool names down to what the skill allows. */
    public Set<String> filterAllowed(Set<String> allTools) {
        if (allowedTools.isEmpty()) {
            return allTools;
        }
        return allTools.stream()
            .filter(allowedTools::contains)
            .collect(Collectors.toSet());
    }

    /** Returns true if any specific tool filtering was defined. */
    public boolean hasToolRestrictions() {
        return !allowedTools.isEmpty() || !declaredTools.isEmpty();
    }
}
