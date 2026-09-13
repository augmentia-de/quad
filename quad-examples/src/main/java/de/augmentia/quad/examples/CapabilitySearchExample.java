package de.augmentia.quad.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.capability.Capability;
import de.augmentia.quad.core.capability.CapabilitySearch;
import de.augmentia.quad.core.capability.CapabilitySearchAgent;
import de.augmentia.quad.core.capability.HnswCapabilityIndex;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.CapabilitySearchTool;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Example for the {@link CapabilitySearchTool} — a hybrid tool that
 * finds candidates via vector search and selects the best match via LLM.
 *
 * <h3>Architecture</h3>
 * <pre>
 * LLM calls capability_search(task="...")
 *   │
 *   ├── 1. Vector search: CapabilitySearch.search(task, topK=20)
 *   │      → 20 candidates from the index (keyword/embedding matching)
 *   │
 *   └── 2. LLM analysis: AnalysisAgent.run(prompt)
 *          → Structured JSON with tool IDs
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 * // 1. Create CapabilitySearch with index
 * var index = new HnswCapabilityIndex();
 * index.index(new Capability("websearch", "Searches the web", ...));
 * index.index(new Capability("readFile", "Reads file contents", ...));
 * var capSearch = new CapabilitySearch(index, 100, 60);
 *
 * // 2. Create AnalysisAgent with LLM
 * var analysisAgent = new CapabilitySearchTool.AnalysisAgent();
 * analysisAgent.setLlm(ModelFactory.createOpenAiFromEnv());
 *
 * // 3. Register CapabilitySearchTool
 * var tool = new CapabilitySearchTool(capSearch, analysisAgent);
 * registry.register("capability_search", tool);
 *
 * // 4. Or automatically via StandardToolProvider (when ChatModel is injected)
 * </pre>
 *
 * @see CapabilitySearchTool
 * @see CapabilitySearch
 * @see ReActSubAgentExample
 */
public class CapabilitySearchExample {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ──────────────────────────────────────────────────────────────
    //  Sub-Agent: minimal agent subclass
    // ──────────────────────────────────────────────────────────────

    public static class DataAnalystAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  String-based tools (for demo registry)
    // ──────────────────────────────────────────────────────────────

    static ToolMethod stringTool(String name, String description,
                                  JsonObjectSchema schema,
                                  java.util.function.BiFunction<String, AgentSessionState, String> executor) {

        ToolSpecification.Builder specBuilder = ToolSpecification.builder()
                .name(name)
                .description(description);
        if (schema != null) {
            specBuilder.parameters(schema);
        }
        ToolSpecification spec = specBuilder.build();

        return new ToolMethod() {
            @Override
            public ToolSpecification spec() { return spec; }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
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
    //  Build CapabilitySearch with demo data
    // ──────────────────────────────────────────────────────────────

    private static CapabilitySearch buildCapabilitySearch() {
        var index = new HnswCapabilityIndex();

        index.index(new Capability("search_web",
            "Searches the web for information on a given topic",
            "search_web", "builtin", "tool"));
        index.index(new Capability("read_database",
            "Reads records from a database table with optional filtering",
            "read_database", "builtin", "tool"));
        index.index(new Capability("calculate",
            "Performs a simple calculation and returns the result",
            "calculate", "builtin", "tool"));
        index.index(new Capability("read_file",
            "Reads the contents of a file from the workspace",
            "readFile", "builtin", "tool"));
        index.index(new Capability("write_file",
            "Writes content to a file in the workspace",
            "writeFile", "builtin", "tool"));
        index.index(new Capability("list_directory",
            "Lists directory contents with optional recursion and details",
            "listDirectory", "builtin", "tool"));
        index.index(new Capability("grep_search",
            "Searches file contents using regular expressions",
            "grepSearch", "builtin", "tool"));
        index.index(new Capability("web_fetch",
            "Fetches content from a URL",
            "webfetch", "builtin", "tool"));
        index.index(new Capability("execute_bash",
            "Executes a bash command in the sandbox",
            "executeBash", "builtin", "tool"));
        index.index(new Capability("data_analyst",
            "Analyzes data and produces insights (Sub-Agent)",
            "data_analyst", "subagent", "skill"));

        return new CapabilitySearch(index, 100, 60);
    }

    // ──────────────────────────────────────────────────────────────
    //  Agent classes
    // ──────────────────────────────────────────────────────────────

    public static class OrchestratorAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }

        public String formatReport(String data) {
            return """
                ╔══════════════════════════════════════╗
                ║         DATA ANALYSIS REPORT         ║
                ╠══════════════════════════════════════╣
                %s
                ╚══════════════════════════════════════╝
                """.formatted(data);
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Main: 3 demos
    // ──────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║   CapabilitySearchTool Example                          ║");
        System.out.println("║   Hybrid: Vektor-Suche + LLM-Auswahl                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        String task = args.length > 0
                ? String.join(" ", args)
                : "Analysiere den aktuellen Stand der KI-Agenten-Technologie";

        try {
            demo1DirectSearch(task);
            demo2WithSubAgent(task);
            demo3NoIndex();
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
     * Demo 1: Call CapabilitySearchTool directly.
     * <p>
     * The tool searches the index with the task description,
     * finds matching candidates and delegates the final selection to the LLM.
     *
     * @param task the task to process
     */
    private static void demo1DirectSearch(String task) throws Exception {
        System.out.println("━━━ Demo 1: Direkte Tool-Suche ━━━");
        System.out.println("Task: " + task);
        System.out.println();

        CapabilitySearch capSearch = buildCapabilitySearch();

        CapabilitySearchAgent analysisAgent = new CapabilitySearchAgent(
            ModelFactory.createOpenAiFromEnv());

        CapabilitySearchTool tool = new CapabilitySearchTool(capSearch, analysisAgent, null, 10, 3);

        System.out.println("Tool: " + tool.spec().name());
        System.out.println("Beschreibung: " + tool.spec().description());
        System.out.println();

        AgentSessionState state = new AgentSessionState();
        String result = tool.execute("{\"task\": \"" + task + "\"}", state).text();
        System.out.println("Ergebnis:");
        System.out.println(result);
        System.out.println();
    }

    /**
     * Demo 2: CapabilitySearchTool in an agent with a sub-agent.
     * <p>
     * The orchestrator has the capability_search tool and a
     * DataAnalystAgent as a sub-agent. The LLM decides whether to
     * search for tools or invoke the sub-agent.
     *
     * @param task the task to process
     */
    private static void demo2WithSubAgent(String task) {
        System.out.println("━━━ Demo 2: Agent mit Sub-Agent + Capability Search ━━━");
        System.out.println("Task: " + task);
        System.out.println();

        CapabilitySearch capSearch = buildCapabilitySearch();

        CapabilitySearchAgent analysisAgent = new CapabilitySearchAgent(
            ModelFactory.createOpenAiFromEnv());

        CapabilitySearchTool capSearchTool = new CapabilitySearchTool(capSearch, analysisAgent, null, 10, 3);

        DataAnalystAgent analyst = new DataAnalystAgent();
        analyst.setLlm(ModelFactory.createOpenAiFromEnv());
        QuadToolRegistry analystRegistry = new QuadToolRegistry(new ToolArgsMapper(MAPPER));
        analystRegistry.register("search_web", stringTool("search_web",
            "Searches the web for information",
            JsonObjectSchema.builder().addStringProperty("query", "The search query").required("query").build(),
            (json, s) -> "Results for: " + jsonField(json, "query")));
        analystRegistry.register("calculate", stringTool("calculate",
            "Performs a calculation",
            JsonObjectSchema.builder().addStringProperty("expression", "Math expression").required("expression").build(),
            (json, s) -> "Result: " + jsonField(json, "expression")));
        analyst.setToolRegistry(analystRegistry);

        Agent orchestrator = AgentBuilder.create(OrchestratorAgent.class)
                .withLlmFromEnv()
                .withSubAgent("data_analyst", analyst)
                .build();
        orchestrator.addHook(new StdoutLoggingHook());

        orchestrator.getToolRegistry().register("capability_search", capSearchTool);

        System.out.println("Parent-Agent Tools:");
        for (var tm : orchestrator.getToolRegistry().getAll()) {
            System.out.println("  - " + tm.spec().name() + ": " + tm.spec().description());
        }
        System.out.println();

        String result = orchestrator.executeReAct(task);
        System.out.println("Antwort: " + result);
        System.out.println();
    }

    /**
     * Demo 3: CapabilitySearchTool without an index (LLM only).
     * <p>
     * When no {@link CapabilitySearch} is configured, the vector
     * search is skipped and the LLM analyzes the task directly.
     */
    private static void demo3NoIndex() throws Exception {
        System.out.println("━━━ Demo 3: Ohne Index (nur LLM) ━━━");

        CapabilitySearchAgent analysisAgent = new CapabilitySearchAgent(
            ModelFactory.createOpenAiFromEnv());

        CapabilitySearchTool tool = new CapabilitySearchTool(null, analysisAgent, null, 10, 3);

        AgentSessionState state = new AgentSessionState();
        String result = tool.execute("{\"task\": \"Read a CSV file and calculate the average\"}", state).text();
        System.out.println("Ergebnis:");
        System.out.println(result);
        System.out.println();
    }
}
