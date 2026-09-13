package de.augmentia.quad.examples;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.SubAgentTool;

/**
 * An orchestrator agent that calls {@link ResearchSubAgent} as a sub-tool.
 * <p>
 * Demonstrates three execution modes:
 * <ol>
 *   <li><b>{@code execute(prompt)}</b> — Single-Shot: one LLM call, no tool loop</li>
 *   <li><b>{@code executeReAct(prompt)}</b> — ReAct loop: iterative tool usage
 *       until the agent provides a final text answer</li>
 *   <li><b>{@code SubAgentTool}</b> — A sub-agent is registered as a tool and
 *       can be called by the parent via a tool call; both agents share
 *       the session state</li>
 * </ol>
 *
 * <h3>Structure</h3>
 * <pre>
 * OrchestratorAgent (Parent)
 * ├── own tools: summarize, lookupPolicy
 * └── Sub-Agent: research (via SubAgentTool)
 *     └── own tools: search, lookupFile
 * </pre>
 *
 * <h3>Key Points</h3>
 * <ul>
 *   <li>The parent does <b>not</b> see the sub-agent's tools directly — only the
 *       {@code research} tool (SubAgentTool)</li>
 *   <li>When {@code research} is called, the prompt is delegated to the sub-agent,
 *       which runs {@code executeReAct} with its own tools</li>
 *   <li>Findings from the sub-agent land in the shared {@link AgentSessionState}</li>
 * </ul>
 *
 * @see ResearchSubAgent
 * @see Agent#executeReAct(String)
 * @see SubAgentTool
 */
public class OrchestratorAgent extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    /**
     * Summarizes a text into key bullet points.
     *
     * @param text the text to summarize
     * @return summary
     */
    @Tool(description = "Summarizes a text into key bullet points")
    public String summarize(@Param("text") String text) {
        return "Summary:\n- " + text.substring(0, Math.min(100, text.length())).replace("\n", " - ") + "...";
    }

    /**
     * Looks up the internal policy for a given topic.
     *
     * @param topic the topic
     * @return the policy
     */
    @Tool(description = "Looks up the internal policy for a given topic")
    public String lookupPolicy(@Param("topic") String topic) {
        return "Policy for '%s': All enterprise agents must log tool executions and share session state.".formatted(topic);
    }

    /**
     * Creates a new orchestrator agent with an LLM from environment variables
     * and registers a simple logging hook that prints all calls to
     * {@code System.out}.
     *
     * @see StdoutLoggingHook
     */
    public OrchestratorAgent() {
        initLlm();
        addHook(new StdoutLoggingHook());
    }

    /**
     * Single-Shot mode: exactly one LLM call, no tool loop.
     *
     * @param prompt the task
     * @return the direct LLM answer
     */
    public String singleShot(String prompt) {
        return execute(prompt);
    }

    /**
     * ReAct mode: iterative tool usage (Reason → Act → Observation).
     * <p>
     * The agent can perform up to {@code maxIterations} (default 10) LLM calls
     * before delivering the final answer.
     *
     * @param prompt the task
     * @return the final answer after tool interaction
     */
    public String reactMode(String prompt) {
        return executeReAct(prompt);
    }

    /**
     * ReAct mode with explicit session state.
     * <p>
     * Allows sharing findings across multiple agent calls by passing
     * the same {@link AgentSessionState}.
     *
     * @param prompt the task
     * @param state  the session state (shared with the sub-agent)
     * @return the final answer
     */
    public String reactMode(String prompt, AgentSessionState state) {
        return executeReAct(prompt, state);
    }
}
