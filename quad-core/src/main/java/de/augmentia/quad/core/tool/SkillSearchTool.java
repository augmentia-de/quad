package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lists skills, filters by keyword, and points the agent to load_skill for full instructions.
 *
 * <p>This tool is for DISCOVERY only (Phase 1). To actually load a skill's full instructions,
 * the agent should call <code>load_skill("skillName")</code>.
 */
public class SkillSearchTool implements ToolMethod {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, Skill> skills;
    private final SkillActivationCallback callback;
    private final ToolSpecification spec;

    @FunctionalInterface
    public interface SkillActivationCallback {
        String activate(String skillName);
    }

    public SkillSearchTool(Map<String, Skill> skills, SkillActivationCallback callback) {
        this.skills = skills;
        this.callback = callback;
        this.spec = ToolSpecification.builder()
            .name("skill_search")
            .description("Search available skills and get their descriptions. "
                + "Without arguments, lists all skills. Use 'query' to filter, "
                + "or 'skillName' to get a hint about a specific skill. "
                + "To load the FULL instructions, use load_skill with the skill name.")
            .build();
    }

    @Override
    public ToolSpecification spec() { return spec; }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception {
        var node = MAPPER.readTree(jsonArguments != null ? jsonArguments : "{}");
        var query = node.has("query") ? node.get("query").asText(null) : null;
        var skillName = node.has("skillName") ? node.get("skillName").asText(null) : null;

        if (query != null && skillName != null) {
            return ToolResult.success("Only one of query/skillName may be set.");
        }

        if (skillName != null) {
            return ToolResult.success(getSkillHint(skillName));
        }
        return ToolResult.success(listSkills(query));
    }

    private String getSkillHint(String skillName) {
        var skill = skills.get(skillName);
        if (skill == null) {
            var available = skills.keySet().stream().sorted().collect(Collectors.joining(", "));
            return "Skill '" + skillName + "' not found.\nAvailable: " + available
                + "\n\nUse load_skill(\"" + skillName + "\") to load the full instructions.";
        }
        var sb = new StringBuilder();
        sb.append("Skill: ").append(skill.name()).append("\n");
        sb.append("Description: ").append(skill.description()).append("\n\n");
        sb.append("To load the FULL instructions (step-by-step guide, allowed tools, examples),\n");
        sb.append("call: load_skill(\"").append(skill.name()).append("\")");
        return sb.toString();
    }

    private String listSkills(String query) {
        var matching = query != null
            ? skills.values().stream()
                .filter(s -> s.name().toLowerCase().contains(query.toLowerCase())
                    || s.description().toLowerCase().contains(query.toLowerCase()))
                .toList()
            : List.copyOf(skills.values());

        if (matching.isEmpty()) {
            return "No skills found" + (query != null ? " for '" + query + "'" : "") + ".";
        }

        var sb = new StringBuilder("Available skills:\n");
        for (var s : matching)
            sb.append("- ").append(s.name()).append(": ").append(s.description()).append("\n");
        sb.append("\nUse load_skill with skillName to load the full instructions for a skill.");
        return sb.toString();
    }
}