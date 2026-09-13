package de.augmentia.quad.core.capability.skill;

import java.util.List;
import java.util.Map;

public record SkillParseResult(
    String skillId,
    String description,
    String body,
    String name,
    List<String> allowedTools,
    List<String> declaredTools,
    Map<String, Object> metadata,
    String license,
    String compatibility
) {
    public SkillParseResult(String skillId, String description, String body, String name, List<String> allowedTools) {
        this(skillId, description, body, name, allowedTools, List.of(), Map.of(), null, null);
    }
}
