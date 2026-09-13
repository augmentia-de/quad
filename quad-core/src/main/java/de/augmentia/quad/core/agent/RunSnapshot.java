package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.session.CwdManager;
import de.augmentia.quad.core.hook.pipeline.HookRegistry;
import de.augmentia.quad.core.tool.ToolMethod;
import java.util.List;

public record RunSnapshot(
    String systemPrompt,
    List<ToolMethod> toolMethods,
    HookRegistry hookRegistry,
    int maxToolIterations,
    CwdManager cwdManager
) {}
