package de.augmentia.quad.examples;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.SubAgentTool;

/**
 * Complete example for <b>ReAct loop</b> and <b>sub-agents</b> in quad.
 * <p>
 * This example demonstrates the three execution modes of an agent:
 *
 * <h3>1. Single-Shot ({@code execute})</h3>
 * <p>
 * The agent makes exactly one LLM call and returns the answer.
 * Tools are offered to the LLM but not executed iteratively:
 * <pre>
 *   String answer = agent.execute("What is a ReAct agent?");
 *   // → one LLM call, no tool loop
 * </pre>
 *
 * <h3>2. ReAct Loop ({@code executeReAct})</h3>
 * <p>
 * The agent uses the Reason → Act → Observation loop:
 * <pre>
 *   String answer = agent.executeReAct("Research the project status");
 *   // → LLM calls tools, results are appended,
 *   //   repeated until final text answer or maxIterations
 * </pre>
 *
 * <h3>3. Sub-Agent via {@code SubAgentTool}</h3>
 * <p>
 * A second agent is registered as a tool. The parent agent can invoke it via
 * a tool call. The sub-agent runs its own ReAct loop and
 * shares the session state:
 * <pre>
 *   Agent orchestrator = AgentBuilder.create(OrchestratorAgent.class)
 *       .withSubAgent("research", new ResearchSubAgent())
 *       .withLlmFromEnv()
 *       .build();
 *
 *   String result = orchestrator.executeReAct(
 *       "Research the market status and summarize it");
 *   // → LLM calls "research" tool → sub-agent runs its own ReAct loop
 * </pre>
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Environment variable {@code OPENAI_API_KEY} with a valid key</li>
 *   <li>Environment variable {@code OPENAI_MODEL} (optional, default: {@code gpt-4o})</li>
 * </ul>
 *
 * <h3>Execution</h3>
 * <pre>
 *   cd quad
 *   mvn install -pl quad-core -DskipTests -q
 *   mvn compile -pl quad-examples -q
 *   mvn exec:java -pl quad-examples \
 *       -Dexec.mainClass=de.augmentia.quad.examples.ReActSubAgentExample
 * </pre>
 *
 * @see OrchestratorAgent
 * @see ResearchSubAgent
 * @see Agent#executeReAct(String)
 * @see SubAgentTool
 */
public class ReActSubAgentExample {

    /**
     * Starts the ReAct + Sub-Agent example.
     * <p>
     * The three modes are demonstrated one after another. On errors (e.g. missing
     * API key), a helpful error message is printed.
     *
     * @param args optional command-line arguments (used as the prompt)
     */
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║   ReAct & Sub-Agent Example                     ║");
        System.out.println("╚══════════════════════════════════════════════════╝");
        System.out.println();

        String task = args.length > 0
                ? String.join(" ", args)
                : "Recherchiere die neuesten Entwicklungen bei KI-Agenten und erstelle eine Zusammenfassung";

        try {
            demo1SingleShot(task);
            demo2ReActLoop(task);
            demo3SubAgent(task);
            demo4SharedState();
        } catch (Exception e) {
            System.err.println("Fehler: " + e.getMessage());
            System.err.println();
            System.err.println("Stelle sicher, dass OPENAI_API_KEY gesetzt ist:");
            System.err.println("  export OPENAI_API_KEY=sk-...");
            System.exit(1);
        }
    }

    /**
     * Demo 1: Single-Shot — one LLM call without a tool loop.
     *
     * @param task the task to process
     */
    private static void demo1SingleShot(String task) {
        System.out.println("━━━ Demo 1: Single-Shot (execute) ━━━");
        System.out.println("Prompt: " + task);
        System.out.println();

        OrchestratorAgent agent = new OrchestratorAgent();
        String result = agent.singleShot(task);

        System.out.println("Antwort: " + result);
        System.out.println();
    }

    /**
     * Demo 2: ReAct Loop — iterative tool usage until the final answer.
     *
     * @param task the task to process
     */
    private static void demo2ReActLoop(String task) {
        System.out.println("━━━ Demo 2: ReAct-Loop (executeReAct) ━━━");
        System.out.println("Prompt: " + task);
        System.out.println("Max Iterationen: 10 (Default)");
        System.out.println();

        OrchestratorAgent agent = new OrchestratorAgent();
        String result = agent.reactMode(task);

        System.out.println("Antwort: " + result);
        System.out.println();
    }

    /**
     * Demo 3: Sub-Agent — the parent agent delegates to a specialized
     * sub-agent via {@code SubAgentTool}.
     * <p>
     * The orchestrator sees {@code research} as a tool and can invoke it via
     * a tool call. The sub-agent runs its own tools ({@code search},
     * {@code lookupFile}) in its own ReAct loop.
     *
     * @param task the task to process
     */
    private static void demo3SubAgent(String task) {
        System.out.println("━━━ Demo 3: Sub-Agent (SubAgentTool via AgentBuilder) ━━━");
        System.out.println("Prompt: " + task);
        System.out.println();

        ResearchSubAgent researchAgent = new ResearchSubAgent();

        Agent orchestrator = AgentBuilder.create(OrchestratorAgent.class)
                .withLlmFromEnv()
                .withSubAgent("research", researchAgent)
                .build();

        System.out.println("Sub-Agent 'research' registriert mit Tools: search, lookupFile");
        System.out.println("Parent-Agent hat Tools: summarize, lookupPolicy, research (SubAgentTool)");
        System.out.println();

        // The sub-agent must also have an LLM (called via executeReAct)
        // The parent agent is configured with an LLM from env by the AgentBuilder.
        // The sub-agent (researchAgent) gets its LLM from the constructor.
        String result = orchestrator.executeReAct(task);

        System.out.println("Antwort: " + result);
        System.out.println();
    }

    /**
     * Demo 4: Shared session state — parent and sub-agent share
     * the same {@link AgentSessionState}.
     * <p>
     * Findings from the sub-agent (e.g. search results) are visible in the parent
     * and vice versa. This enables collaborative agent architectures.
     */
    private static void demo4SharedState() {
        System.out.println("━━━ Demo 4: Gemeinsamer Session-State ━━━");
        System.out.println();

        AgentSessionState state = new AgentSessionState();
        state.addFinding("[parent] Initialisiere Analyse...");
        state.setCurrentProject("KI-Agenten-Portal");

        System.out.println("State vor Sub-Agent-Aufruf:");
        System.out.println("  Findings:       " + state.findings());
        System.out.println("  CurrentProject: " + state.getCurrentProject());
        System.out.println();

        ResearchSubAgent researchAgent = new ResearchSubAgent();

        Agent orchestrator = AgentBuilder.create(OrchestratorAgent.class)
                .withLlmFromEnv()
                .withSubAgent("research", researchAgent)
                .build();

        String result = orchestrator.executeReAct(
                "Research the project status for the AI agent portal", state);

        System.out.println("State nach Sub-Agent-Aufruf:");
        System.out.println("  Findings:       " + state.findings());
        System.out.println("  CurrentProject: " + state.getCurrentProject());
        System.out.println();
        System.out.println("Antwort: " + result);
        System.out.println();

        System.out.println("Note: The sub-agent has added its own findings to the shared state.");
        System.out.println("         Beide Agents arbeiten auf derselben Session-State-Instanz.");
    }
}
