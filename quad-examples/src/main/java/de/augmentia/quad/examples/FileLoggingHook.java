package de.augmentia.quad.examples;

import de.augmentia.quad.core.hook.pipeline.AgentHook;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;

/**
 * {@link AgentHook} that writes all lifecycle events to a file.
 *
 * <h3>Usage</h3>
 * <pre>
 * FileLogger logger = new FileLogger("logs/orchestrator");
 * agent.addHook(new FileLoggingHook(logger));
 * </pre>
 *
 * @see FileLogger
 * @see AgentHook
 */
public class FileLoggingHook implements AgentHook {

    private final FileLogger logger;

    public FileLoggingHook(FileLogger logger) {
        this.logger = logger;
    }

    @Override
    public String name() {
        return "file-logging";
    }

    @Override
    public HookResult beforeAgent(HookContexts.BeforeAgentContext ctx) {
        logger.logMultiLine("beforeAgent",
                "sessionId=" + ctx.sessionId() + " prompt=" + truncate(ctx.prompt(), 2000));
        return HookResult.Continue.INSTANCE;
    }

    @Override
    public HookResult afterAgent(HookContexts.AfterAgentContext ctx, String response) {
        logger.logMultiLine("afterAgent ",
                "sessionId=" + ctx.sessionId() + " result=" + truncate(response, 5000));
        return HookResult.Continue.INSTANCE;
    }

    @Override
    public HookResult beforeModelCall(HookContexts.BeforeModelCallContext ctx) {
        logger.log("beforeModelCall",
                "sessionId=" + ctx.sessionId()
                        + " messages=" + ctx.messages().size()
                        + " tools=" + ctx.tools().size());
        return HookResult.Continue.INSTANCE;
    }

    @Override
    public HookResult afterModelCall(HookContexts.AfterModelCallContext ctx, String llmResponse) {
        logger.log("afterModelCall",
                "sessionId=" + ctx.sessionId()
                        + " tokens=" + ctx.inputTokens() + "+" + ctx.outputTokens());
        logger.logMultiLine("afterModelCall", truncate(llmResponse, 3000));
        return HookResult.Continue.INSTANCE;
    }

    @Override
    public HookResult beforeToolCall(HookContexts.BeforeToolCallContext ctx) {
        logger.logMultiLine("beforeToolCall",
                "sessionId=" + ctx.sessionId()
                        + " tool=" + ctx.toolName()
                        + " args=" + ctx.arguments());
        return HookResult.Continue.INSTANCE;
    }

    @Override
    public HookResult afterToolCall(HookContexts.AfterToolCallContext ctx, String toolResult) {
        logger.logMultiLine("afterToolCall",
                "sessionId=" + ctx.sessionId()
                        + " tool=" + ctx.toolName()
                        + " error=" + ctx.isError());
        logger.logMultiLine("afterToolCall", truncate(toolResult, 3000));
        return HookResult.Continue.INSTANCE;
    }

    static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + " chars)";
    }
}
