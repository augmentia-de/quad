package de.augmentia.quad.core.hook.plugin;

import de.augmentia.quad.core.capability.skill.Skill;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Hook that detects when tool_activator activates a tool declared by a skill.
 * In the new JIT model, the agent uses load_skill directly — this hook is
 * kept for backward compatibility with declared-tools workflows.
 */
public class SkillActivationHook implements Plugin {

    private final Map<String, Skill> toolToSkill = new HashMap<>();
    private final Consumer<String> onSkillActivated;

    public SkillActivationHook(Map<String, Skill> toolToSkill, Consumer<String> onSkillActivated) {
        this.toolToSkill.putAll(toolToSkill);
        this.onSkillActivated = onSkillActivated;
    }

    @Override public String name() { return "quad:skill-activation"; }
    @Override public int order() { return 5; }

    @Override
    public HookResult afterToolCall(HookContexts.AfterToolCallContext ctx, String toolResult) {
        if (!"tool_activator".equals(ctx.toolName())) return HookResult.Continue.INSTANCE;
        var skill = findActivatedSkill(toolResult);
        if (skill == null) return HookResult.Continue.INSTANCE;
        if (onSkillActivated != null) {
            onSkillActivated.accept(skill.name());
        }
        return HookResult.Continue.INSTANCE;
    }

    private Skill findActivatedSkill(String result) {
        for (var entry : toolToSkill.entrySet()) {
            if (result.contains("'" + entry.getKey() + "'")) return entry.getValue();
        }
        return null;
    }
}