package de.augmentia.quad.examples.orchestrator;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.DynamicSubAgentTool;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrator-Agent for dynamic task decomposition and sub-agent execution.
 *
 * @see DynamicSubAgentTool
 */
public class OrchestratorAgent extends Agent {

    private static final String SYSTEM_PROMPT = """
            You are an Orchestrator-Agent. Break tasks down into steps and execute them sequentially.

            AVAILABLE TOOLS (ALWAYS provide all required parameters!):

            1. decompose_task(task="...")
               - Decomposes a task into 2-4 steps
               - Parameter: task (String, required) - the complete task description
               - Returns: JSON array of steps

            2. execute_step(prompt="...", tools=["tool1","tool2"], skills=["skill1"])
               - Executes a step with a sub-agent
               - Parameter: prompt (String, required) - the concrete task
               - Parameter: tools (String-Array, optional) - tool names like "websearch","read_file"
               - Parameter: skills (String-Array, optional) - skill names for instructions in the sub-agent
               - Returns: result of the sub-agent

            3. get_results()
               - Shows all collected findings
               - No parameters needed

            4. format_report(title="...", content="...")
               - Formats a report
               - Parameter: title (String, required)
               - Parameter: content (String, required)

            5. capability_search(task="...")
               - Searches for matching tools for a task
               - Parameter: task (String, required)

            6. skill_search(query="...", skillName="...")
               - Searches and activates skills
               - Parameter: query (String, optional) - search term for filtering
               - Parameter: skillName (String, optional) - skill name for details + activation
               - Without parameters: lists all available skills

            WORKFLOW:
            1. Call decompose_task with the full task
            2. Check if skills match the steps (skill_search)
            3. For each step: Call execute_step with prompt, matching tools AND skills
            4. Call get_results to check progress
            5. Summarize everything with format_report

            IMPORTANT RULES:
            - Provide required parameters for EVERY tool call (not empty!)
            - Execute steps sequentially (not in parallel)
            - When done, respond with DONE or RESULT: your summary
            - Do NOT call any more tools after the last step
            """;

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Decomposes a task into 2-4 structured steps. Returns a JSON array "
            + "with 'step', 'description', and 'tools_needed' for each step.")
    public String decompose_task(@Param("task") String task) {
        DecomposeAgent agent = new DecomposeAgent();
        agent.setLlm(llm);
        // Kein ToolRegistry — DecomposeAgent braucht nur das LLM, keine Tools
        return agent.decompose(task);
    }

    @Tool(description = "Returns all accumulated findings from the current session. "
            + "Use this to review progress and results from previous steps.")
    public String get_results(AgentSessionState state) {
        var findings = state.findings();
        if (findings.isEmpty()) {
            return "No findings yet.";
        }
        StringBuilder sb = new StringBuilder("Accumulated findings:\n");
        for (int i = 0; i < findings.size(); i++) {
            sb.append(i + 1).append(". ").append(findings.get(i)).append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "Formats a report with a title and content into a structured box")
    public String format_report(
            @Param("title") String title,
            @Param("content") String content) {
        if (title == null) title = "Untitled";
        if (content == null) content = "(no content)";
        return """
                ╔══════════════════════════════════════════════╗
                ║  %s
                ╠══════════════════════════════════════════════╣
                %s
                ╚══════════════════════════════════════════════╝
                """.formatted(
                title,
                content.lines()
                        .map(l -> "║  " + l)
                        .reduce("", (a, b) -> a + b + "\n"));
    }

    @Override
    public List<ChatMessage> initialMessages(String prompt, AgentSessionState state) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        messages.add(new dev.langchain4j.data.message.UserMessage(prompt));
        return messages;
    }
}
