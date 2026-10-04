package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Just-in-time skill loading: loads the full skill guide only
 * when the agent actually needs the skill.
 *
 * <p>Analogy to every other tool:
 * <pre>
 *   Phase 1 (startup):  skill catalog (name + description) in the system prompt
 *   Phase 2 (runtime):   load_skill("name") → tool call → full instructions as tool result
 * </pre>
 *
 * <p>This prevents context bloat: the 200+ lines of instructions of a SKILL.md
 * are only in the context when the agent explicitly requests them.
 */
public class SkillLoadTool implements ToolMethod {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Skill> skills;
    private final ToolSpecification spec;

    public SkillLoadTool(Map<String, Skill> skills) {
        this.skills = skills != null ? new LinkedHashMap<>(skills) : new LinkedHashMap<>();
        this.spec = ToolSpecification.builder()
                .name("load_skill")
                .description("Load the full instructions for a skill by name. "
                        + "Use this when your task matches a skill's description. "
                        + "Returns the complete step-by-step instructions, allowed tools, and examples.")
                .parameters(JsonObjectSchema.builder()
                    .addStringProperty("skillName", "The exact name of the skill to load")
                    .required("skillName")
                    .build())
                .build();
    }

    @Override
    public ToolSpecification spec() {
        return spec;
    }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception {
        if (jsonArguments == null || jsonArguments.isBlank()) {
            return ToolResult.error("skillName parameter is required");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(jsonArguments);
        } catch (Exception e) {
            return ToolResult.error("Invalid JSON arguments: " + e.getMessage());
        }

        String skillName = node.has("skillName") && !node.get("skillName").isNull()
                ? node.get("skillName").asText(null) : null;

        if (skillName == null || skillName.isBlank()) {
            return ToolResult.error("skillName parameter is required");
        }

        Skill skill = skills.get(skillName);
        if (skill == null) {
            var available = new StringBuilder();
            for (var name : skills.keySet()) {
                if (available.length() > 0) available.append(", ");
                available.append(name);
            }
            return ToolResult.error("Skill '" + skillName + "' not found."
                    + (available.length() > 0 ? " Available: " + available : ""));
        }

        // inject the full instructions into the context as a tool result
        var sb = new StringBuilder();
        sb.append("## Skill loaded: ").append(skill.name()).append("\n\n");
        if (skill.description() != null && !skill.description().isBlank()) {
            sb.append("**Description**: ").append(skill.description()).append("\n\n");
        }
        if (skill.instructions() != null && !skill.instructions().isBlank()) {
            sb.append("### Instructions\n\n");
            sb.append(skill.instructions()).append("\n\n");
        }
        if (!skill.allowedTools().isEmpty()) {
            sb.append("### Allowed tools: ").append(String.join(", ", skill.allowedTools())).append("\n");
        }
        if (!skill.declaredTools().isEmpty()) {
            sb.append("### Declared tools: ").append(String.join(", ", skill.declaredTools())).append("\n");
        }
        if (skill.license() != null && !skill.license().isBlank()) {
            sb.append("### License: ").append(skill.license()).append("\n");
        }
        return ToolResult.success(sb.toString());
    }

    /** Returns the skill catalog (Name → Skill) for Phase-1 injection. */
    public Map<String, Skill> getSkills() {
        return Map.copyOf(skills);
    }
}