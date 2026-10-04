package de.augmentia.quad.core.hook.plugin;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import dev.langchain4j.data.message.SystemMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin that injects a skill catalog (name + description) before every LLM call.
 *
 * <h3>Two-Phase Skill Loading (Just-in-Time)</h3>
 * <p><b>Phase 1 (startup):</b> only the skill catalog is inserted into the system prompt —
 * name + short description. That costs 20-50 tokens per skill and prevents
 * context bloat.
 *
 * <p><b>Phase 2 (runtime):</b> if the agent needs a skill, it calls
 * <code>load_skill("skillName")</code>. The tool loads the full
 * instructions (200+ lines) into the context only as a tool result.
 *
 * <p>Dies ist analog zu jedem anderen Tool: Name+Description im Schema →
 * Function Call → Result im Context.
 */
public class AgentSkillsPlugin implements Plugin {

    private final Map<String, Skill> skills;
    private final List<String> initialSkills;
    private final boolean skillSearchEnabled;
    private String lastInjectedCatalog = "";
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

    /**
     * Phase 1: injects ONLY the skill catalog (name + description).
     * The full instructions are NOT injected — they only enter
     * the context via load_skill() as a tool result.
     */
    @Override
    public HookResult beforeModelCall(HookContexts.BeforeModelCallContext ctx) {
        if (skills.isEmpty()) return HookResult.Continue.INSTANCE;
        String catalog = generateSkillCatalog();
        if (catalog.equals(lastInjectedCatalog)) return HookResult.Continue.INSTANCE;
        ctx.additionalMessages().add(SystemMessage.from(catalog));
        lastInjectedCatalog = catalog;
        return HookResult.Continue.INSTANCE;
    }

    /**
     * Phase 1: skill catalog — name + description per skill.
     * No instructions, no allowed-tools, no examples.
     */
    private String generateSkillCatalog() {
        var buf = new StringBuilder();
        buf.append("## Available Skills\n\n");
        buf.append("Use the load_skill tool with a skill name to load the full instructions when your task matches a skill description.\n\n");
        for (var s : skills.values()) {
            buf.append("- **").append(escapeXml(s.name())).append("**");
            if (s.description() != null && !s.description().isBlank()) {
                buf.append(": ").append(escapeXml(s.description()));
            }
            buf.append("\n");
        }
        if (skillSearchEnabled) {
            buf.append("\nYou can also use skill_search to find skills by keyword.");
        }
        return buf.toString();
    }

    public Map<String, Skill> skills() {
        return Map.copyOf(skills);
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}