package de.augmentia.quad.core.hook.pipeline;

/**
 * Hook interface for intercepting agent lifecycle events.
 */
public interface AgentHook {

    String name();

    default int order() { return 0; }

    default HookResult beforeAgent(HookContexts.BeforeAgentContext ctx) {
        return HookResult.Continue.INSTANCE;
    }

    default HookResult afterAgent(HookContexts.AfterAgentContext ctx, String response) {
        return HookResult.Continue.INSTANCE;
    }

    default HookResult beforeModelCall(HookContexts.BeforeModelCallContext ctx) {
        return HookResult.Continue.INSTANCE;
    }

    default HookResult afterModelCall(HookContexts.AfterModelCallContext ctx, String llmResponse) {
        return HookResult.Continue.INSTANCE;
    }

    default HookResult beforeToolCall(HookContexts.BeforeToolCallContext ctx) {
        return HookResult.Continue.INSTANCE;
    }

    default HookResult afterToolCall(HookContexts.AfterToolCallContext ctx, String toolResult) {
        return HookResult.Continue.INSTANCE;
    }
}
