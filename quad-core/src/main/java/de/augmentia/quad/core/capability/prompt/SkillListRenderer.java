package de.augmentia.quad.core.capability.prompt;

import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.capability.skill.SkillRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;

/**
 * Renderer for the Skill-Catalog (Phase 1) and Tool-List (Phase 2).
 *
 * <h3>Two-Phase Skill Loading</h3>
 * <p><b>Phase 1 (Startup):</b> renderCatalog() — Name + Description only (20-50 Tokens/Skill).
 * <p><b>Phase Runtime):</b> load_skill tool call → full Instructions as Tool-Result.
 */
public class SkillListRenderer {

    public SkillListRenderer() {}

    /**
     * Phase 1: Skill-Catalog — Name + Description pro Skill.
     * No instructions, no allowed-tools, no examples.
     * That prevents context bloat: 200+ lines of instructions only come on request.
     */
    public String renderCatalog(String base, SkillRegistry registry) {
        if (registry == null || registry.listDiscovered().isEmpty()) {
            return base;
        }
        var sb = new StringBuilder(base);
        sb.append("\n\n## Available Skills\n\n");
        sb.append("Use the load_skill tool with a skill name to load the full instructions when your task matches a skill description.\n\n");
        for (var name : registry.listDiscovered()) {
            Skill s = registry.getSkill(name);
            if (s == null) continue;
            sb.append("- **").append(s.name()).append("**");
            if (s.description() != null && !s.description().isBlank()) {
                sb.append(": ").append(s.description());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public String renderTools(List<?> tools) {
        if (tools == null || tools.isEmpty()) {
            return "No tools available";
        }
        StringBuilder sb = new StringBuilder();
        for (Object obj : tools) {
            if (obj instanceof ToolSpecification spec) {
                sb.append("- ")
                  .append(spec.name())
                  .append(": ")
                  .append(spec.description())
                  .append("\n  Parameter: ")
                  .append(renderParameters(spec.parameters()))
                  .append("\n\n");
            } else {
                sb.append("- ").append(obj.toString()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    public String renderWithTools(String template, List<?> toolSpecs) {
        if (toolSpecs == null || toolSpecs.isEmpty()) {
            return template;
        }
        String toolsSection = renderTools(toolSpecs);
        return template.replace("{tools}", toolsSection);
    }

    private String renderParameters(Object parameters) {
        if (parameters == null) {
            return "None";
        }
        if (parameters instanceof dev.langchain4j.model.chat.request.json.JsonObjectSchema schema) {
            return schema.properties().entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue().description())
                .reduce((a, b) -> a + ", " + b)
                .orElse("None");
        }
        return parameters.toString();
    }
}