package de.augmentia.quad.examples;

import de.augmentia.quad.core.hook.pipeline.AgentHook;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;

/**
 * Simple {@link AgentHook} that writes all lifecycle events to
 * {@code System.out}.
 * <p>
 * Useful for debugging, as a starting point for custom monitoring,
 * and as a template for production-ready hooks (e.g. with SLF4J
 * or Micrometer metrics).
 *
 * <h3>Output Format</h3>
 * <pre>
 * [HOOK] beforeAgent      sessionId=... prompt=...
 * [HOOK] beforeModelCall  sessionId=... messages=3 tools=2
 * [HOOK] afterModelCall   sessionId=... tokens=120+85 response=...
 * [HOOK] beforeToolCall   sessionId=... tool=search args={query: ...}
 * [HOOK] afterToolCall    sessionId=... tool=search error=false result=...
 * [HOOK] afterAgent       sessionId=... result=...
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 * agent.addHook(new StdoutLoggingHook());
 * </pre>
 *
 * @see AgentHook
 * @see HookContexts
 */
public class StdoutLoggingHook implements AgentHook {

    @Override
    public String name() {
        return "stdout-logging";
    }

    @Override
    public HookResult beforeAgent(HookContexts.BeforeAgentContext ctx) {
        System.out.println("[HOOK] beforeAgent      sessionId=" + ctx.sessionId()
                + " prompt=" + truncate(ctx.prompt(), 120));
        return HookResult.Continue.INSTANCE;
    }

    @Override
    public HookResult afterAgent(HookContexts.AfterAgentContext ctx, String response) {
        System.out.println("[HOOK] afterAgent       sessionId=" + ctx.sessionId()
                + " result=" + truncate(response, 720));
        return HookResult.Continue.INSTANCE;
    }

    @Override
    public HookResult beforeModelCall(HookContexts.BeforeModelCallContext ctx) {
        System.out.println("[HOOK] beforeModelCall  sessionId=" + ctx.sessionId()
                + " messages=" + ctx.messages().size()
                + " tools=" + ctx.tools().size());
        return HookResult.Continue.INSTANCE;
    }

    @Override
    public HookResult afterModelCall(HookContexts.AfterModelCallContext ctx, String llmResponse) {
        System.out.println("[HOOK] afterModelCall   sessionId=" + ctx.sessionId()
                + " tokens=" + ctx.inputTokens() + "+" + ctx.outputTokens()
                + " response=" + truncate(llmResponse, 120));
        return HookResult.Continue.INSTANCE;
    }

    @Override
    public HookResult beforeToolCall(HookContexts.BeforeToolCallContext ctx) {
        System.out.println("[HOOK] beforeToolCall   sessionId=" + ctx.sessionId()
                + " tool=" + ctx.toolName()
                + " args=" + ctx.arguments());
        return HookResult.Continue.INSTANCE;
    }

    @Override
    public HookResult afterToolCall(HookContexts.AfterToolCallContext ctx, String toolResult) {
        System.out.println("[HOOK] afterToolCall    sessionId=" + ctx.sessionId()
                + " tool=" + ctx.toolName()
                + " error=" + ctx.isError()
                + " result=" + truncate(toolResult, 12000));
        return HookResult.Continue.INSTANCE;
    }

    /**
     * Truncates a string to a maximum of {@code max} characters.
     * If exceeded, {@code ...(N chars)} is appended.
     *
     * @param s   the input string
     * @param max maximum length
     * @return truncated string
     */
    static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(" + s.length() + " chars)";
    }
}
