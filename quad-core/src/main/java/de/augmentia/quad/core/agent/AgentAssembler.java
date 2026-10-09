package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.capability.CapabilityRegistry;
import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.hook.plugin.AgentSkillsPlugin;
import de.augmentia.quad.core.hook.plugin.SkillActivationHook;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.SkillLoadTool;
import de.augmentia.quad.core.tool.SkillSearchTool;
import de.augmentia.quad.core.tool.ToolActivatorTool;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reusable assembly helpers shared by {@link AgentBuilder} and external builders
 * (for example the standalone {@code quad-workflow-worker}).
 *
 * <p>Extracted so the two-phase skill loading is defined exactly once instead of being
 * re-implemented per consumer.</p>
 */
public final class AgentAssembler {

    private AgentAssembler() {
    }

    /**
     * Wires the two-phase Just-in-Time skill loading onto an agent and its registry:
     * <ol>
     *   <li>{@link AgentSkillsPlugin} injects only the skill catalog (name + description).</li>
     *   <li>{@code load_skill} loads full instructions on demand.</li>
     *   <li>{@code skill_search} (optional) discovers skills at runtime.</li>
     *   <li>{@link ToolActivatorTool} enables dynamic tool registration.</li>
     *   <li>{@link SkillActivationHook} reacts to tool activation as a skill switch.</li>
     * </ol>
     *
     * @param agent             target agent
     * @param registry          its tool registry
     * @param skillDirs         skill directories to discover (no-op when empty)
     * @param initialSkills     skills active at start (max 3)
     * @param skillSearchEnabled whether to expose the {@code skill_search} tool
     */
    public static void registerSkills(Agent agent, QuadToolRegistry registry,
                                      List<Path> skillDirs, List<String> initialSkills,
                                      boolean skillSearchEnabled) {
        if (agent == null || registry == null || skillDirs == null || skillDirs.isEmpty()) {
            return;
        }

        var capabilityRegistry = CapabilityRegistry.builder()
                .skillDirs(skillDirs.toArray(new Path[0]))
                .build();
        var allSkills = capabilityRegistry.discoverAllSkills();
        var skillMap = new HashMap<String, Skill>();
        for (var s : allSkills) {
            skillMap.put(s.name(), s);
        }

        // 1. AgentSkillsPlugin — phase 1: injects ONLY the skill catalog (name + description)
        agent.addHook(new AgentSkillsPlugin(allSkills,
                initialSkills != null ? initialSkills : List.of(), skillSearchEnabled));

        // 2. load_skill tool — phase 2: just-in-time loading of the full instructions
        var loadSkillTool = new SkillLoadTool(skillMap);
        registry.register(loadSkillTool.spec().name(), loadSkillTool);

        // 3. SkillSearchTool — discovery only (points to load_skill for full instructions)
        if (skillSearchEnabled) {
            var skillSearchTool = new SkillSearchTool(skillMap, null);
            registry.register(skillSearchTool.spec().name(), skillSearchTool);
        }

        // 4. ToolActivatorTool — dynamic tool registration
        var toolActivator = new ToolActivatorTool(registry);
        registry.register(toolActivator.spec().name(), toolActivator);

        // 5. SkillActivationHook — tool activation is recognized as a skill switch
        var toolToSkill = new HashMap<String, Skill>();
        for (var s : allSkills) {
            if (s.declaredTools() != null) {
                for (var tool : s.declaredTools()) {
                    toolToSkill.put(tool, s);
                }
            }
        }
        agent.addHook(new SkillActivationHook(toolToSkill, null));
    }
}
