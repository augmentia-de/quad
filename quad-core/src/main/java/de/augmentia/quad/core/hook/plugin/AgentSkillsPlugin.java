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
 * Plugin, das vor jedem LLM-Call einen Skill-Catalog (Name + Description) injiziert.
 *
 * <h3>Two-Phase Skill Loading (Just-in-Time)</h3>
 * <p><b>Phase 1 (Startup):</b> Nur das Skill-Catalog wird im System Prompt eingefügt —
 * Name + kurze Beschreibung. Das kostet 20-50 Tokens pro Skill und verhindert
 * Context Bloat.
 *
 * <p><b>Phase 2 (Runtime):</b> Wenn der Agent einen Skill braucht, ruft er
 * <code>load_skill("skillName")</code> auf. Das Tool lädt die vollständigen
 * Instructions (200+ Zeilen) erst als Tool-Result in den Context.
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
     * Phase 1: Injiziert NUR das Skill-Catalog (Name + Description).
     * Die vollständigen Instructions werden NICHT injiziert — die kommen erst
     * über load_skill() als Tool-Result in den Context.
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
     * Phase 1: Skill-Catalog — Name + Description pro Skill.
     * Keine Instructions, keine allowed-tools, keine Examples.
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