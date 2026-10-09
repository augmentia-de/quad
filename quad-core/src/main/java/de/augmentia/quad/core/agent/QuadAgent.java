package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.session.AgentSessionState;

/**
 * Ready-to-use, configurable default agent.
 *
 * <p>Created through {@link #create()}, which wires an LLM from environment variables
 * ({@code OPENAI_API_KEY}, {@code OPENAI_MODEL}), logging, tool registration, event
 * publishing and the hook registry via {@link AgentBuilder}. Everything beyond that —
 * guardrails, skills, HITL, channels, structured output, sub-agents — stays opt-in via
 * builder methods or setters.</p>
 *
 * <pre>{@code
 *   QuadAgent agent = QuadAgent.create();
 *   agent.run("Your request");
 * }</pre>
 */
public class QuadAgent extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    /**
     * Builds a fully wired default agent. Uses the same {@link AgentBuilder} path as any
     * configured agent, so tools declared with {@code @Tool} are registered as well.
     */
    public static QuadAgent create() {
        return AgentBuilder.create(QuadAgent.class)
                .withLlmFromEnv()
                .withLogging()
                .build();
    }

    public static void main(String[] args) {
        System.out.println(QuadAgent.create().run("Briefly explain what an AI assistant is"));
    }
}
