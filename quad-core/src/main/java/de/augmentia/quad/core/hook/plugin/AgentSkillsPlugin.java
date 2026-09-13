package de.augmentia.quad.core.hook.plugin;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import de.augmentia.quad.core.tool.ToolMethod;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Plugin, das vor jedem LLM-Call einen XML-Block mit Skills injiziert.
 * Generates {@code <activated_skills>} and {@code <available_skills>} blocks.
 */
public class AgentSkillsPlugin implements Plugin {

    private final Map<String, Skill> skills;
    private final List<String> initialSkills;
    private final boolean skillSearchEnabled;
    private String lastInjectedXml = "";
    private Agent agent;

    public AgentSkillsPlugin(List<Skill> skills, List<String> initialSkills, boolean skillSearchEnabled) {
        this.skills = new LinkedHashMap<>();
        for (var s : skills) this.skills.put(s.name(), s);
        this.initialSkills = initialSkills != null ? List.copyOf(initialSkills) : List.of();
        if (this.initialSkills.size() > 3)
            throw new IllegalArgumentException("initialSkills max 3");
        this.skillSearchEnabled = skillSearchEnabled;
    }

    @Override public String name() { return "quad:agent-skills"; }
    @Override public int order() { return 10; }

    @Override
    public void initAgent(Agent agent) { this.agent = agent; }

    @Override
    public HookResult beforeModelCall(HookContexts.BeforeModelCallContext ctx) {
        if (skills.isEmpty()) return new HookResult.Continue();
        var xml = generateSkillsXml();
        if (xml.equals(lastInjectedXml)) return new HookResult.Continue();
        ctx.additionalMessages().add(
            dev.langchain4j.data.message.SystemMessage.from(xml));
        lastInjectedXml = xml;
        return new HookResult.Continue();
    }

    private String generateSkillsXml() {
        var buf = new StringBuilder();

        if (!initialSkills.isEmpty()) {
            buf.append("<activated_skills>\n");
            for (var name : initialSkills) {
                var s = skills.get(name);
                if (s != null) {
                    buf.append("<skill name=\"").append(escapeXml(s.name())).append("\">\n");
                    buf.append(s.instructions() != null ? s.instructions() : s.description());
                    buf.append("\n");
                    if (s.allowedTools() != null && !s.allowedTools().isEmpty())
                        buf.append("<allowed_tools>").append(String.join(", ", s.allowedTools()))
                           .append("</allowed_tools>\n");
                    buf.append("</skill>\n");
                }
            }
            buf.append("</activated_skills>\n\n");
        }

        buf.append("<available_skills>\n");
        if (skills.isEmpty()) {
            buf.append("No skills are currently available.\n");
        } else {
            for (var s : skills.values()) {
                buf.append("<skill>\n");
                buf.append("<name>").append(escapeXml(s.name())).append("</name>\n");
                buf.append("<description>").append(escapeXml(s.description())).append("</description>\n");
                if (s.path() != null)
                    buf.append("<location>").append(escapeXml(s.path().toString())).append("</location>\n");
                buf.append("</skill>\n");
            }
        }
        buf.append("</available_skills>\n");

        if (skillSearchEnabled) {
            buf.append("\nUse the skill_search tool to search and activate skills.\n");
        }
        return buf.toString();
    }

    @Override
    public List<ToolMethod> getTools() {
        return List.of();
    }

    public String activateSkill(String skillName) {
        var skill = skills.get(skillName);
        if (skill == null) {
            var available = skills.keySet().stream().sorted().collect(Collectors.joining(", "));
            return "Skill '" + skillName + "' not found.\nAvailable: " + available;
        }
        var sb = new StringBuilder();
        sb.append("Skill activated: ").append(skill.name()).append("\n\n");
        sb.append(skill.instructions() != null ? skill.instructions() : skill.description());
        if (skill.allowedTools() != null && !skill.allowedTools().isEmpty())
            sb.append("\n\nAllowed tools: ").append(String.join(", ", skill.allowedTools()));
        return sb.toString();
    }

    public Map<String, Skill> skills() { return skills; }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
