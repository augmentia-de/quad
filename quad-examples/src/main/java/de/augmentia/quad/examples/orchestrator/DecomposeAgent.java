package de.augmentia.quad.examples.orchestrator;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.data.message.SystemMessage;

import java.util.List;

/**
 * Agent that decomposes a task into structured steps.
 * Used by OrchestratorAgent.decompose_task().
 */
public class DecomposeAgent extends Agent {

    private static final String DECOMPOSE_SYSTEM_PROMPT = """
            You are a Task-Decomposer. Break tasks down into 2-4 concrete, actionable steps.

            ANSWER ONLY WITH A JSON-ARRAY (no markdown, no text before or after):
            [{"step":1,"description":"What needs to be done","tools_needed":["websearch","read_file"]}]

            Available tool names: websearch, read_file, write_file, calculate, grep_search, list_directory, execute_bash, web_fetch

            each step should be concrete and actionable.""";

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Override
    public List<dev.langchain4j.data.message.ChatMessage> initialMessages(
            String prompt, AgentSessionState state) {
        return List.of(
                new SystemMessage(DECOMPOSE_SYSTEM_PROMPT),
                new dev.langchain4j.data.message.UserMessage(prompt));
    }

    public String decompose(String task) {
        System.out.println("[decompose_task] task=" + (task.length() <= 100 ? task : task.substring(0, 100) + "..."));
        String result = run(task);
        System.out.println("[decompose_task] result=" + (result != null && result.length() <= 200
                ? result : result == null ? "null" : result.substring(0, 200) + "...(" + result.length() + " chars)"));
        return result;
    }
}
