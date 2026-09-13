package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lists skills, filters by keyword, activates skills, and provides instructions.
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
            .description("Search available skills and activate them. "
                + "Without arguments, lists all skills. Use 'query' to filter, "
                + "or 'skillName' to load full instructions.")
            .build();
    }

    @Override
    public ToolSpecification spec() { return spec; }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception {
        var node = MAPPER.readTree(jsonArguments);
        var query = node.has("query") ? node.get("query").asText(null) : null;
        var skillName = node.has("skillName") ? node.get("skillName").asText(null) : null;

        if (query != null && skillName != null) {
            return ToolResult.success("Only one of query/skillName may be set.");
        }

        if (skillName != null) {
            return ToolResult.success(activateSkill(skillName));
        }
        return ToolResult.success(listSkills(query));
    }

    private String activateSkill(String skillName) {
        var skill = skills.get(skillName);
        if (skill == null) {
            var available = skills.keySet().stream().sorted().collect(Collectors.joining(", "));
            return "Skill '" + skillName + "' not found.\nAvailable: " + available;
        }
        if (callback != null) {
            return callback.activate(skillName);
        }
        var sb = new StringBuilder();
        sb.append("Skill activated: ").append(skill.name()).append("\n\n");
        sb.append(skill.instructions() != null ? skill.instructions() : skill.description());
        if (skill.allowedTools() != null && !skill.allowedTools().isEmpty())
            sb.append("\n\nAllowed tools: ").append(String.join(", ", skill.allowedTools()));
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
        sb.append("\nUse skill_search with skillName to activate a skill.");
        return sb.toString();
    }
}
