package de.augmentia.quad.examples;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.tool.SubAgentTool;

/**
 * A specialized sub-agent that handles research tasks.
 * <p>
 * This agent has its own tools ({@code search}, {@code lookupFile}) and
 * is called by the {@link OrchestratorAgent} as a {@code SubAgentTool}.
 * It uses {@link Agent#executeReAct(String, AgentSessionState)}
 * to iteratively call tools until it can provide a final answer.
 * <p>
 * <b>Key Concepts:</b>
 * <ul>
 *   <li>Sub-agents share the {@link AgentSessionState} with the parent agent
 *       (shared session state)</li>
 *   <li>The sub-agent can have its own dedicated tools that the
 *       parent does not see directly</li>
 *   <li>Recursion depth is limited via {@code SubAgentTool} (default: 5)</li>
 * </ul>
 *
 * @see OrchestratorAgent
 * @see SubAgentTool
 */
public class ResearchSubAgent extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    /**
     * Simulates a web search. In production, a {@code WebSearchTool}
     * or {@code WebFetchTool} would be used here.
     *
     * @param query the search query
     * @return simulated search results
     */
    @Tool(description = "Searches the web for information on a given topic")
    public String search(@Param("query") String query) {
        AgentSessionState state = getCurrentState();
        if (state != null) {
            state.addFinding("[search] Query: " + query);
        }
        return "Results for '%s':\n- Article: 'Enterprise Agent Architecture Patterns'\n- Article: 'ReAct Loops in Practice'\n- Article: 'Sub-Agent Orchestration'".formatted(query);
    }

    /**
     * Reads a file from the workspace.
     *
     * @param path path relative to the workspace
     * @return file content (simulated here)
     */
    @Tool(description = "Reads a file from the workspace and returns its content")
    public String lookupFile(@Param("path") String path) {
        AgentSessionState state = getCurrentState();
        if (state != null) {
            state.addFinding("[lookup] Read file: " + path);
        }
        return "=== File: %s ===\nTopic: AI Agent Design Patterns\nStatus: Published\nVersion: 2.1".formatted(path);
    }

    /**
     * Reads the current session state from the thread-local context.
     *
     * @return the current {@link AgentSessionState} or {@code null}
     */
    private AgentSessionState getCurrentState() {
        try {
            var current = CurrentSession.getCurrent();
            return current;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Creates a new {@link ResearchSubAgent} with LLM configuration
     * from environment variables ({@code OPENAI_API_KEY}, {@code OPENAI_MODEL}).
     */
    public ResearchSubAgent() {
        initLlm();
        addHook(new StdoutLoggingHook());
    }
}
