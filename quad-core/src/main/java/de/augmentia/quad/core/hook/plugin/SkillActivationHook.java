package de.augmentia.quad.core.hook.plugin;

import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Hook that injects the matching skill instruction after tool_activator calls.
 * Detects which tool was activated, finds the corresponding skill, and injects
 * the instructions as a DynamicBlock into the context.
 */
public class SkillActivationHook implements Plugin {

    private final Map<String, Skill> toolToSkill = new HashMap<>();
    private final AgentSkillsPlugin skillsPlugin;

    public SkillActivationHook(Map<String, Skill> toolToSkill, AgentSkillsPlugin skillsPlugin) {
        this.toolToSkill.putAll(toolToSkill);
        this.skillsPlugin = skillsPlugin;
    }

    @Override public String name() { return "quad:skill-activation"; }
    @Override public int order() { return 5; }

    @Override
    public HookResult afterToolCall(HookContexts.AfterToolCallContext ctx, String toolResult) {
        if (!"tool_activator".equals(ctx.toolName())) return new HookResult.Continue();
        var skill = findActivatedSkill(toolResult);
        if (skill == null) return new HookResult.Continue();
        skillsPlugin.activateSkill(skill.name());
        return new HookResult.Continue();
    }

    private Skill findActivatedSkill(String result) {
        for (var entry : toolToSkill.entrySet()) {
            if (result.contains("'" + entry.getKey() + "'")) return entry.getValue();
        }
        return null;
    }
}
