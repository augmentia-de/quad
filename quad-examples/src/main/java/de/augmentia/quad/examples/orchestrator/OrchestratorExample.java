package de.augmentia.quad.examples.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.capability.*;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.ScopedSessionState;
import de.augmentia.quad.core.tool.CapabilitySearchTool;
import de.augmentia.quad.core.tool.DynamicSubAgentTool;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolResult;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.SkillSearchTool;
import de.augmentia.quad.examples.FileLogger;
import de.augmentia.quad.examples.FileLoggingHook;
import de.augmentia.quad.examples.StdoutLoggingHook;
import de.augmentia.quad.core.capability.skill.Skill;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;

import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * Orchestrator example: Dynamic sub-agent creation with selective state sharing.
 * <p>
 * An orchestrator agent receives a freely defined task, decomposes it
 * into steps, finds matching tools via {@link CapabilitySearchTool} and
 * dynamically creates sub-agents for each step. Session state is selectively
 * shared via {@link ScopedSessionState}.
 *
 * <h3>Architecture</h3>
 * <pre>
 * OrchestratorAgent (Parent)
 * ├── Tools: decompose_task, get_results, format_report
 * ├── CapabilitySearchTool (hybrid: Vector + LLM)
 * └── DynamicSubAgentTool (creates sub-agents at runtime)
 *     ├── Step 1: Sub-Agent with [websearch] → ScopedSessionState
 *     ├── Step 2: Sub-Agent with [read_file, calculate] → ScopedSessionState
 *     └── Step 3: Sub-Agent with [write_file] → ScopedSessionState
 * </pre>
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Environment variable {@code OPENAI_API_KEY} with a valid key</li>
 * </ul>
 *
 * <h3>Execution</h3>
 * <pre>
 * cd quad
 * mvn install -pl quad-core -DskipTests -q
 * mvn compile -pl quad-examples -q
 * mvn exec:java -pl quad-examples \
 *     -Dexec.mainClass=de.augmentia.quad.examples.orchestrator.OrchestratorExample
 * </pre>
 */
public class OrchestratorExample {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ──────────────────────────────────────────────────────────────
    //  String-based tools (demo registry for sub-agents)
    // ──────────────────────────────────────────────────────────────

    static ToolMethod stringTool(String name, String description,
                                  java.util.function.BiFunction<String, AgentSessionState, String> executor) {
        ToolSpecification spec = ToolSpecification.builder()
                .name(name)
                .description(description)
                .build();
        return new ToolMethod() {
            @Override public ToolSpecification spec() { return spec; }
            @Override public ToolResult execute(String jsonArguments, AgentSessionState state) {
                return ToolResult.success(executor.apply(jsonArguments, state));
            }
        };
    }

    static String jsonField(String json, String field) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode n = node.get(field);
            return n != null ? n.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  CapabilitySearch with demo data
    // ──────────────────────────────────────────────────────────────

    private static CapabilitySearch buildCapabilitySearch() {
        var index = new HnswCapabilityIndex();

        index.index(new Capability("websearch",
            "Searches the web for information on a given topic",
            "websearch", "builtin", "tool"));
        index.index(new Capability("read_file",
            "Reads the contents of a file from the workspace",
            "read_file", "builtin", "tool"));
        index.index(new Capability("write_file",
            "Writes content to a file in the workspace",
            "write_file", "builtin", "tool"));
        index.index(new Capability("calculate",
            "Performs a calculation and returns the result",
            "calculate", "builtin", "tool"));
        index.index(new Capability("grep_search",
            "Searches file contents using regular expressions",
            "grep_search", "builtin", "tool"));
        index.index(new Capability("list_directory",
            "Lists directory contents",
            "list_directory", "builtin", "tool"));
        index.index(new Capability("execute_bash",
            "Executes a bash command in the sandbox",
            "execute_bash", "builtin", "tool"));
        index.index(new Capability("web_fetch",
            "Fetches content from a URL",
            "web_fetch", "builtin", "tool"));

        return new CapabilitySearch(index, 100, 60);
    }

    // ──────────────────────────────────────────────────────────────
    //  Sub-Agent Tools (demo registry)
    // ──────────────────────────────────────────────────────────────

    private static QuadToolRegistry buildDemoToolRegistry() {
        QuadToolRegistry registry = new QuadToolRegistry(new ToolArgsMapper(MAPPER));

        registry.register("websearch", stringTool("websearch",
            "Searches the web for information on a topic",
            (args, state) -> {
                String query = jsonField(args, "query");
                return "Web search results for '" + query + "':\n"
                    + "- AI agents are becoming mainstream in 2026\n"
                    + "- Multi-agent architectures are trending\n"
                    + "- Tool-use patterns: ReAct, Plan-and-Execute\n"
                    + "- Key frameworks: LangChain4j, CrewAI, AutoGen";
            }));

        registry.register("read_file", stringTool("read_file",
            "Reads the contents of a file",
            (args, state) -> {
                String path = jsonField(args, "path");
                return "File content of '" + path + "':\n"
                    + "The quick brown fox jumps over the lazy dog.\n"
                    + "Line 2: Additional data for analysis.\n"
                    + "Line 3: More interesting findings.";
            }));

        registry.register("write_file", stringTool("write_file",
            "Writes content to a file",
            (args, state) -> {
                String path = jsonField(args, "path");
                String content = jsonField(args, "content");
                state.addFinding("[write_file] Written to " + path + ": " + content.length() + " chars");
                return "Successfully wrote to " + path;
            }));

        registry.register("calculate", stringTool("calculate",
            "Performs a calculation",
            (args, state) -> {
                String expr = jsonField(args, "expression");
                return "Result of '" + expr + "': 42";
            }));

        registry.register("grep_search", stringTool("grep_search",
            "Searches file contents using regex",
            (args, state) -> {
                String pattern = jsonField(args, "pattern");
                return "Grep results for '" + pattern + "':\n"
                    + "match1.txt:42: found pattern\n"
                    + "match2.txt:7: another match";
            }));

        registry.register("list_directory", stringTool("list_directory",
            "Lists directory contents",
            (args, state) -> {
                String path = jsonField(args, "path");
                return "Contents of '" + path + "':\n"
                    + "  file1.txt\n  file2.csv\n  subdirectory/";
            }));

        registry.register("execute_bash", stringTool("execute_bash",
            "Executes a bash command",
            (args, state) -> {
                String cmd = jsonField(args, "command");
                return "Bash output for '" + cmd + "':\nExecution completed successfully.";
            }));

        registry.register("web_fetch", stringTool("web_fetch",
            "Fetches content from a URL",
            (args, state) -> {
                String url = jsonField(args, "url");
                return "Content from '" + url + "':\nPage content with relevant data.";
            }));

        return registry;
    }

    // ──────────────────────────────────────────────────────────────
    //  Create orchestrator
    // ──────────────────────────────────────────────────────────────

    private static Agent buildOrchestrator(ChatModel chatModel, CapabilitySearch capSearch, FileLogger logger) {
        QuadToolRegistry demoRegistry = buildDemoToolRegistry();

        // CapabilityRegistry — Skills via QUAD_SKILLS_DIR or default
        String skillsDir = System.getenv("QUAD_SKILLS_DIR");
        CapabilityRegistry.Builder capBuilder = CapabilityRegistry.builder();
        if (skillsDir != null && !skillsDir.isBlank()) {
            capBuilder.skillDir(Path.of(skillsDir));
        }
        CapabilityRegistry capRegistry = capBuilder.build();

        DynamicSubAgentTool dynamicTool = new DynamicSubAgentTool(chatModel, demoRegistry, capRegistry);

        CapabilitySearchAgent analysisAgent = new CapabilitySearchAgent(chatModel);
        CapabilitySearchTool capSearchTool = new CapabilitySearchTool(capSearch, analysisAgent, null, 10, 3);

        // SkillSearchTool — search and activate skills
        var allSkills = capRegistry.discoverAllSkills();
        var skillMap = new LinkedHashMap<String, Skill>();
        for (var s : allSkills) skillMap.put(s.name(), s);
        SkillSearchTool skillSearchTool = new SkillSearchTool(skillMap, name -> {
            var skill = skillMap.get(name);
            if (skill == null) return "Skill '" + name + "' not found.";
            return "Skill activated: " + skill.name() + "\n\n"
                    + (skill.instructions() != null ? skill.instructions() : skill.description());
        });

        OrchestratorAgent orchestrator = new OrchestratorAgent();
        orchestrator.setLlm(chatModel);
        orchestrator.addHook(new StdoutLoggingHook());
        orchestrator.addHook(new FileLoggingHook(logger));
        // Sub-agent receives the same hooks + EventPublisher as the orchestrator
        dynamicTool.setHookRegistry(orchestrator.getHookRegistry());
        dynamicTool.setEventPublisher(orchestrator.getEventPublisher());

        QuadToolRegistry registry = new QuadToolRegistry(new ToolArgsMapper(MAPPER));
        registry.registerFromAgent(orchestrator);
        registry.register("execute_step", dynamicTool);
        registry.register("capability_search", capSearchTool);
        registry.register("skill_search", skillSearchTool);
        orchestrator.setToolRegistry(registry);

        return orchestrator;
    }

    // ──────────────────────────────────────────────────────────────
    //  Debug: Display tool specs
    // ──────────────────────────────────────────────────────────────

    private static void debugPrintToolSpecs() throws Exception {
        OrchestratorAgent agent = new OrchestratorAgent();
        QuadToolRegistry registry = new QuadToolRegistry(new ToolArgsMapper(MAPPER));
        registry.registerFromAgent(agent);

        System.out.println("━━━ Debug: Tool-Specs (OrchestratorAgent) ━━━");
        for (ToolMethod tm : registry.getAll()) {
            System.out.println("--- " + tm.spec().name() + " ---");
            System.out.println("  description: " + tm.spec().description());
            System.out.println("  parameters:  " + tm.spec().parameters());
        }
        System.out.println();
    }

    // ──────────────────────────────────────────────────────────────
    //  Main: 4 demos
    // ──────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║   Orchestrator Pipeline Example                         ║");
        System.out.println("║   Dynamische Sub-Agent-Erstellung + State-Sharing       ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        try { debugPrintToolSpecs(); } catch (Exception e) { System.err.println("debug failed: " + e); }

        String task = args.length > 0
                ? String.join(" ", args)
                : "Recherchiere aktuelle KI-Agenten-Trends und erstelle eine Zusammenfassung";

        try {
            ChatModel chatModel = ModelFactory.createOpenAiFromEnv();
            CapabilitySearch capSearch = buildCapabilitySearch();

            try (FileLogger logger = new FileLogger("logs/orchestrator")) {
                //demo1DecomposeTask(chatModel, task, capSearch, logger);
                //demo2SingleStep(chatModel, capSearch, logger);
                demo3FullPipeline(chatModel, capSearch, task, logger);
                demo4SelectiveStateSharing(chatModel, capSearch, logger);
            }
        } catch (Exception e) {
            System.err.println("Fehler: " + e.getMessage());
            e.printStackTrace();
            System.err.println();
            System.err.println("Stelle sicher, dass OPENAI_API_KEY gesetzt ist:");
            System.err.println("  export OPENAI_API_KEY=sk-...");
            System.exit(1);
        }
    }

    /**
     * Demo 1: Task decomposition — the orchestrator decomposes a task into steps.
     *
     * @param chatModel the LLM
     * @param task      the task to decompose
     */
    private static void demo1DecomposeTask(ChatModel chatModel, String task,
            CapabilitySearch capSearch, FileLogger logger) {
        System.out.println("━━━ Demo 1: Task-Zerlegung (decompose_task) ━━━");
        System.out.println("Task: " + task);
        System.out.println();

        Agent orchestrator = buildOrchestrator(chatModel, capSearch, logger);
        String result = orchestrator.executeReAct(
                "Decompose the following task into structured steps. "
                + "Return a JSON array with 'step' (number), 'description' (what to do), "
                + "and 'tools_needed' (list of tool names like 'websearch', 'read_file', etc.).\n\n"
                + "Task: " + task);

        System.out.println("Ergebnis:");
        System.out.println(result);
        System.out.println();
    }

    /**
     * Demo 2: Single step — a sub-agent is dynamically created.
     *
     * @param chatModel the LLM
     * @param capSearch the capability search
     */
    private static void demo2SingleStep(ChatModel chatModel, CapabilitySearch capSearch, FileLogger logger) {
        System.out.println("━━━ Demo 2: Einzelner Step (execute_step) ━━━");
        System.out.println();

        Agent orchestrator = buildOrchestrator(chatModel, capSearch, logger);
        String result = orchestrator.executeReAct(
                "Call execute_step with prompt=\"Research current AI agent trends\" "
                + "and tools=[\"websearch\"]");

        System.out.println("Ergebnis:");
        System.out.println(result);
        System.out.println();
    }

    /**
     * Demo 3: Full pipeline — decomposition + sequential execution + aggregation.
     *
     * @param chatModel the LLM
     * @param capSearch the capability search
     * @param task      the task to process
     */
    private static void demo3FullPipeline(ChatModel chatModel, CapabilitySearch capSearch,
            String task, FileLogger logger) {
        System.out.println("━━━ Demo 3: Volle Pipeline (decompose → execute → aggregate) ━━━");
        System.out.println("Task: " + task);
        System.out.println();

        Agent orchestrator = buildOrchestrator(chatModel, capSearch, logger);
        AgentSessionState state = new AgentSessionState();

        String result = orchestrator.executeReAct(
                "You are an orchestrator. Execute these steps:\n"
                + "1. Call decompose_task with task=\"" + task + "\"\n"
                + "2. For each step: call execute_step with the step text as the prompt\n"
                + "3. Call get_results to collect all findings\n"
                + "4. Summarize the results\n\n"
                + "IMPORTANT: You MUST call the tools, not just talk about them.",
                state);

        System.out.println("═══ Final Results ═══");
        System.out.println("Findings: " + state.findings());
        System.out.println();
        System.out.println("Answer: " + result);
        System.out.println();
    }

    /**
     * Demo 4: Selective state sharing — sub-agents only see shared fields.
     *
     * @param chatModel the LLM
     * @param capSearch the capability search
     */
    private static void demo4SelectiveStateSharing(ChatModel chatModel, CapabilitySearch capSearch,
            FileLogger logger) {
        System.out.println("━━━ Demo 4: Selektives State-Sharing (ScopedSessionState) ━━━");
        System.out.println();

        // Parent state with private findings
        AgentSessionState parentState = new AgentSessionState();
        parentState.setTenantId("acme-corp");
        parentState.setCurrentProject("KI-Agenten-Portal");
        parentState.addFinding("[parent] Private Analyse-Daten");
        parentState.addFinding("[parent] Vertrauliche API-Keys");

        System.out.println("Parent-State:");
        System.out.println("  TenantId:       " + parentState.getTenantId());
        System.out.println("  Project:        " + parentState.getCurrentProject());
        System.out.println("  Findings:       " + parentState.findings());
        System.out.println();

        // Scoped state for sub-agent
        AgentSessionState scoped = ScopedSessionState.childOf(parentState);

        System.out.println("Scoped State (before sub-agent execution):");
        System.out.println("  TenantId:       " + scoped.getTenantId());
        System.out.println("  Project:        " + scoped.getCurrentProject());
        System.out.println("  Findings:       " + scoped.findings());
        System.out.println("  Own Findings:   " + ((ScopedSessionState) scoped).ownFindings());
        System.out.println();

        // Sub-agent adds its own findings
        scoped.addFinding("[sub-agent] Web search completed");
        scoped.addFinding("[sub-agent] Data analyzed");

        System.out.println("Scoped State (nach Sub-Agent-Modifikation):");
        System.out.println("  Findings:       " + scoped.findings());
        System.out.println("  Own Findings:   " + ((ScopedSessionState) scoped).ownFindings());
        System.out.println();

        System.out.println("Parent-State (unchanged):");
        System.out.println("  Findings:       " + parentState.findings());
        System.out.println();

        // Demonstrate CWD isolation
        parentState.setCwd("/workspace/project-a");
        System.out.println("CWD-Isolation:");
        System.out.println("  Parent CWD:     " + parentState.currentCwd());
        System.out.println("  Scoped CWD:     " + scoped.currentCwd());
        System.out.println();

        System.out.println("Zusammenfassung:");
        System.out.println("  - TenantId und Project werden geteilt (lesend)");
        System.out.println("  - Findings: Parent sieht nur eigene, Sub-Agent sieht Parent + eigene");
        System.out.println("  - CWD ist komplett isoliert");
        System.out.println("  - SagaLog und MutationListeners sind nicht geteilt");
        System.out.println();
    }
}
