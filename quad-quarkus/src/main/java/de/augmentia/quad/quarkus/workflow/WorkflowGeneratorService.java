package de.augmentia.quad.quarkus.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.quarkus.mcp.McpManagerService;
import de.augmentia.quad.quarkus.messaging.ChannelAgentFactory;
import de.augmentia.quad.quarkus.ui.SharedState;
import de.augmentia.quad.quarkus.ui.UiConfig;
import de.augmentia.quad.quarkus.contract.ApiDtos;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Uses a dedicated, application-internal <b>Master-Orchestrator</b> agent to decompose a task
 * description into an executable workflow DAG.
 *
 * <p>There is no seeded "planner agent" template (and no configurable planner id): the
 * orchestrator agent is built on the fly inside this service — like the agent-loop
 * {@code ResilientOrchestratorHarness} builds its planner — on top of the single base agent.
 * It receives its own system prompt (the Master-Orchestrator prompt, rendered with the current
 * domain) and its own tool set every single call. The base agent (e.g. {@code default-agent})
 * is the only agent that lives in {@link SharedState} and is reused to execute the generated
 * workflow's steps later.</p>
 *
 * <p>The agent is asked, exactly like the agent-loop planner/orchestrator, to decompose a request
 * into a DAG. Instead of spawning live sub-agents at runtime, it returns the DAG as a JSON object
 * {@code {steps:[{id,description,agent,dependsOn,optional}]}} which is validated (unique ids,
 * existing dependencies, no cycles) and converted into a {@link ApiDtos.WorkflowDef}. In
 * {@code [PLANNING]} mode (first invocation) it decomposes the raw task; in {@code [REVIEW]}
 * mode (interactive chat) it receives the transcript + current steps and returns the full updated
 * DAG.</p>
 */
@ApplicationScoped
public class WorkflowGeneratorService {

    private static final Logger log = Logger.getLogger(WorkflowGeneratorService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_RETRIES = 3;

    // ─────────────────────────────────────────────────────────────────────────────
    // Workflow-Architect system prompt (given verbatim to the orchestrator agent).
    // Placeholder {{restrictedTools}} is rendered per call.
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String WORKFLOW_ARCHITECT_SYSTEM = """
        You are a highly specialized Workflow Architect. Your task is to break down complex user requests into a structured, parallelized or sequential multi-agent workflow.

        ### INPUT
        You will receive:
        1. A goal or objective (User Intent).
        2. A lis of available tools (Tool Repository).

        ### ARCHITECTURE RULES
        1. **Agent Roles (`agentRole`):** Create concise, unique role names (e.g., "SQL-Expert", "Data-Cleaner", "Report-Generator").
        2. **Instructions (`instruction`):** Each instruction must be precise and self-contained. If a task depends on a previous step, explicitly instruct the agent where to find or expect the input data/files from the preceding agent.
        3. **Tools (`tools`):** Assign ONLY tools that exis in the provided Tool Repository. If an agent does not require tools, pass an empty array `[]`.
        4. **Dependencies (`dependsOn`):**
           - Define dependencies ONLY when a step strictly requires the output of a previous step.
           - Maximize parallelism: Independent tasks MUST NOT have `dependsOn` set, allowing them to execute concurrently.
           - **Fork/Join Pattern:** When parallel branches produce results that a downstream step needs to aggregate, that step MUST declare `dependsOn` listing ALL predecessor branches (merge point). The platform auto-inserts a join node at merge points. For terminal parallelism (e.g., broadcasting to multiple recipients where no aggregation is needed), steps may remain independent without a downstream join.
        5. **Timeouts (`timeoutSeconds`):** Default is 120 seconds. Increase up to 300+ seconds for computationally intensive tasks (e.g., builds, complex data processing, test suites).

        CRITICAL: The provided tools of Tool Repository can ONLY be assigned as strings inside the `tools` parameter of `steps`.
            You, the Orchestrator, CANNOT execution-call these tools yourself!

        ### OUTPUT FORMAT
        Respond ONLY with a valid JSON object. Do not include any conversational text or markdown explanation outside the JSON block.

        JSON Schema:
        {
          "workflowName": "Short descriptive name of the workflow",
          "description": "Summary of the overall goal and process",
          "steps": [
            {
              "agentRole": "String",
              "instruction": "String",
              "tools": ["String"],
              "dependsOn": ["String"],
              "timeoutSeconds": Number,
              "systemPrompt": "Optional: dedicated system prompt for this step's agent. Owith to use the platform default.",
              "data": {
                 "output": { "type": "object", "properties": { "field": { "type": "string" } } },
                 "outputDescription": "Human-readable description of the JSON data this step produces and hands to its successors."
              }
            }
          ]
        }

        ### DATA CONTRACT
        - Every step MUST declare the JSON data it produces under `data.output` (a JSON-schema-like object) and a short `data.outputDescription`.
        - The `instruction` MUST tell the step's agent to ewith its result as a JSON object matching `data.output` and MUST explicitly name where to find received predecessor data (e.g. "use the JSON payload named '<agentRole>' from the previous step").
        - Successors consume the predecessor's `data.output` as structured input and MUST reference it in their own `instruction`.
        """;

    @Inject ChannelAgentFactory agentFactory;
    @Inject SharedState state;
    @Inject UiConfig uiConfig;
    @Inject McpManagerService mcpManager;

    /** Active interactive chat sessions (Variante B: echte Server-Chatsession). */
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    /**
     * Start (or reset) an interactive chat session: decomposes the task ([PLANNING]) and keeps
     * the history server-side so the generated workflow can be refined across further requests.
     *
     * @return a descriptor carrying the session id, the initial workflow and the raw reply.
     */
    public ChatDesc startChat(String task, String workflowName) {
        String name = workflowName != null && !workflowName.isBlank() ? workflowName : "Dynamic Workflow";
        String userMessage = planningMessage(task);

        WorkbookResult r = callOrchestrator(userMessage, 0, DEFAULT_MAX_RETRIES);
        List<GeneratedStep> steps = r.steps;

        String sessionId = "chat-" + UUID.randomUUID().toString().substring(0, 8);
        ChatSession s = new ChatSession(sessionId, name, task, steps);
        s.transcript.add("USER: " + task);
        s.transcript.add("ASSISTANT: " + stepsToJson(steps));
        sessions.put(sessionId, s);

        ApiDtos.WorkflowDef wf = toWorkflowDef(sessionId, name, task, steps);
        return new ChatDesc(sessionId, wf, r.raw);
    }

    /**
     * Continue an interactive chat session ([REVIEW]). Appends the user request, sends the complete
     * transcript + current workflow to the orchestrator, replaces the workflow with the returned
     * full workflow, and returns the updated result.
     */
    public ChatDesc chat(String sessionId, String request) {
        ChatSession s = sessions.get(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("Unknown chat session: " + sessionId
                + ". Start one via POST /api/ui/dynamic/chat/start first.");
        }
        s.transcript.add("USER: " + request);

        String userMessage = reviewMessage(s, request);
        WorkbookResult r = callOrchestrator(userMessage, s.transcript.size(), DEFAULT_MAX_RETRIES);
        s.steps = r.steps;
        s.transcript.add("ASSISTANT: " + stepsToJson(r.steps));

        ApiDtos.WorkflowDef wf = toWorkflowDef(sessionId, s.name, s.task, r.steps);
        return new ChatDesc(sessionId, wf, r.raw);
    }

    public List<Map<String, Object>> chatSessions() {
        return sessions.values().stream()
            .sorted(Comparator.comparing(s -> s.name))
            .map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sessionId", s.id);
                m.put("name", s.name);
                m.put("task", s.task);
                m.put("stepCount", s.steps != null ? s.steps.size() : 0);
                return m;
            })
            .toList();
    }

    /** [PLANNING] user message: JSON with the task and the full Tool Repository. */
    private String planningMessage(String task) {
        return buildUserJson(Map.of(
            "task", task,
            "toolList", toolRepository()
        ));
    }

    /** [REVIEW] user message: full transcript + current steps + latest request (JSON). */
    private String reviewMessage(ChatSession s, String request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Original task: ").append(s.task).append("\n\n");
        sb.append("Full conversation so far:\n");
        for (String line : s.transcript) {
            sb.append(line).append("\n");
        }
        sb.append("\nCurrent workflow steps (JSON):\n")
          .append(stepsToJson(s.steps)).append("\n");
        sb.append("\nUser's latest request: ").append(request).append("\n\n");
        sb.append("Update the steps to apply the latest request (you may add/remove/reorder/change "
            + "tasks). Return the FULL updated steps array (never a diff).");
        return sb.toString();
    }

    /** All tools (builtin {@link UiConfig#getEnabledTools()} + MCP) as a sorted list. */
    private List<String> toolRepository() {
        Set<String> all = new LinkedHashSet<>(uiConfig.getEnabledTools());
        if (mcpManager != null && mcpManager.toolNames() != null) {
            all.addAll(mcpManager.toolNames());
        }
        List<String> sorted = new ArrayList<>(all);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    /** Serializes the user JSON {@code {task, toolList}} for the orchestrator call. */
    private String buildUserJson(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize orchestrator user message", e);
        }
    }

    /**
     * Builds (on every call) the dedicated internal Workflow-Architect agent: the rendered
     * system prompt plus the full configured tool set. Not registered in
     * {@link SharedState}; created without a template.
     */
    private Agent orchestratorAgent(int currentRound) {
        ApiDtos.AgentDefinition def = new ApiDtos.AgentDefinition(
            "__generator-orchestrator__",
            "Generator Workflow-Architect",
            "Internal agent that decomposes tasks into workflow DAGs",
            "planning",
            uiConfig.getModel(),
            0.0, 8192, 1.0,
            uiConfig.getEnabledTools().toArray(new String[0]),
            new String[0], new String[0], "ua",
                WORKFLOW_ARCHITECT_SYSTEM, null, false,
            "{\"temperature\":0.0,\"maxOutputTokens\":8192}"
        );
        return agentFactory.buildFromDefinition(def);
    }

    /**
     * Calls the internal orchestrator with retries; parses the returned JSON into steps and
     * returns parsed steps + workflow meta + the last raw reply.
     */
    private WorkbookResult callOrchestrator(String userMessage, int currentRound, int maxRetries) {
        String working = userMessage;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Agent orchestrator = orchestratorAgent(currentRound);
                String raw = orchestrator.execute(working);
                return parseSteps(stripFences(raw));
            } catch (Exception e) {
                log.warn("Orchestrator attempt " + attempt + "/" + maxRetries + " failed", e);
                if (attempt == maxRetries) {
                    throw new RuntimeException(
                        "Decomposition failed after " + maxRetries + " attempts: " + e.getMessage(), e);
                }
                working += "\n\n[SYSTEM: previous attempt failed. Return ONLY valid JSON matching the schema. No markdown.]";
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private String stepsToJson(List<GeneratedStep> steps) {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (GeneratedStep s : steps) {
            arr.add(new LinkedHashMap<>() {{
                put("agentRole", s.agentRole());
                put("instruction", s.instruction());
                put("tools", s.tools());
                put("dependsOn", s.dependsOn());
                put("timeoutSeconds", s.timeoutSeconds());
                put("systemPrompt", s.systemPrompt());
                put("data", s.data());
            }});
        }
        try {
            return MAPPER.writeValueAsString(arr);
        } catch (Exception e) {
            return String.valueOf(steps);
        }
    }

    /** Generates a workflow definition from a task description with the default retry count. */
    public ApiDtos.WorkflowDef generate(String task, String workflowName) {
        return generate(task, workflowName, DEFAULT_MAX_RETRIES);
    }

    /** Generates a workflow definition, retrying the LLM call up to maxRetries times. */
    public ApiDtos.WorkflowDef generate(String task, String workflowName, int maxRetries) {
        WorkbookResult r = callOrchestrator(planningMessage(task), 0, maxRetries);
        String name = workflowName != null && !workflowName.isBlank() ? workflowName
            : (r.workflowName != null && !r.workflowName.isBlank() ? r.workflowName : "Dynamic Workflow");

        String id = "workflow-dyn-" + UUID.randomUUID().toString().substring(0, 8);
        return toWorkflowDef(id, name, task, r.steps);
    }

    /** Parses the Workflow-Architect JSON output into {@link GeneratedStep}s + workflow meta. */
    WorkbookResult parseSteps(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.has("steps") || !root.get("steps").isArray()) {
                throw new IllegalArgumentException("JSON missing 'steps' array");
            }
            String workflowName = root.hasNonNull("workflowName")
                ? root.get("workflowName").asText() : null;
            String description = root.hasNonNull("description")
                ? root.get("description").asText() : null;
            List<GeneratedStep> steps = new ArrayList<>();
            Map<String, GeneratedStep> byRole = new LinkedHashMap<>();
            for (JsonNode sn : root.get("steps")) {
                String agentRole = sn.hasNonNull("agentRole") ? sn.get("agentRole").asText() : "";
                if (agentRole.isBlank()) {
                    throw new IllegalArgumentException("Step is missing 'agentRole'");
                }
                String instruction = sn.hasNonNull("instruction") ? sn.get("instruction").asText() : "";
                List<String> tools = new ArrayList<>();
                if (sn.has("tools") && sn.get("tools").isArray()) {
                    for (JsonNode t : sn.get("tools")) tools.add(t.asText());
                }
                List<String> dependsOn = new ArrayList<>();
                if (sn.has("dependsOn") && sn.get("dependsOn").isArray()) {
                    for (JsonNode dep : sn.get("dependsOn")) dependsOn.add(dep.asText());
                }
                int timeoutSeconds = sn.hasNonNull("timeoutSeconds") && !sn.get("timeoutSeconds").isNull()
                    ? sn.get("timeoutSeconds").asInt() : 120;
                String systemPrompt = sn.hasNonNull("systemPrompt")
                    ? sn.get("systemPrompt").asText() : null;
                Map<String, Object> data = null;
                if (sn.has("data") && sn.get("data").isObject()) {
                    data = MAPPER.convertValue(sn.get("data"),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                }
                GeneratedStep s = new GeneratedStep(
                    agentRole, instruction, tools, dependsOn, timeoutSeconds, systemPrompt, data);
                if (byRole.put(agentRole, s) != null) {
                    throw new IllegalArgumentException("Duplicate agentRole: " + agentRole);
                }
                steps.add(s);
            }
            if (steps.isEmpty()) throw new IllegalArgumentException("Parsed 0 steps from JSON");
            validateSteps(steps, byRole);
            return new WorkbookResult(steps, workflowName, description, json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON from planner: " + e.getMessage(), e);
        }
    }

    private void validateSteps(List<GeneratedStep> steps, Map<String, GeneratedStep> byRole) {
        for (GeneratedStep s : steps) {
            for (String dep : s.dependsOn()) {
                if (!byRole.containsKey(dep)) {
                    throw new IllegalArgumentException(
                        "Step '" + s.agentRole() + "' depends on non-existent step '" + dep + "'");
                }
            }
        }
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        for (GeneratedStep s : steps) {
            if (hasCycle(s.agentRole(), byRole, visited, stack)) {
                throw new IllegalArgumentException(
                    "Cycle detected in DAG involving step '" + s.agentRole() + "'");
            }
        }
    }

    private boolean hasCycle(String role, Map<String, GeneratedStep> byRole, Set<String> visited, Set<String> stack) {
        if (stack.contains(role)) return true;
        if (visited.contains(role)) return false;
        visited.add(role);
        stack.add(role);
        GeneratedStep s = byRole.get(role);
        if (s != null) {
            for (String dep : s.dependsOn()) {
                if (hasCycle(dep, byRole, visited, stack)) return true;
            }
        }
        stack.remove(role);
        return false;
    }

    ApiDtos.WorkflowDef toWorkflowDef(String id, String name, String initialTask,
                                     List<GeneratedStep> steps) {
        // 1. Create + persis one dedicated (inactive) agent per generated step, so every node
        //    executes with its own system prompt / tool set (persisted immediately, active=false).
        Map<String, String> roleToAgentId = new LinkedHashMap<>();
        for (GeneratedStep s : steps) {
            roleToAgentId.put(s.agentRole(), createStepAgent(s));
        }

        Map<String, String> roleToNodeId = new LinkedHashMap<>();
        Map<String, Map<String, Object>> configByNode = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        // 2. Build the step nodes (mutable maps so joins + templates can be injected afterwards).
        for (GeneratedStep s : steps) {
            String nodeId = "node-" + s.agentRole();
            roleToNodeId.put(s.agentRole(), nodeId);
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("agentId", roleToAgentId.get(s.agentRole()));
            config.put("tools", s.tools() != null ? new ArrayList<>(s.tools()) : List.of());
            config.put("timeoutSeconds", s.timeoutSeconds());
            String systemPrompt = s.systemPrompt() != null && !s.systemPrompt().isBlank()
                ? s.systemPrompt() : null;
            if (systemPrompt != null) {
                config.put("systemPrompt", systemPrompt);
            }
            if (s.data() != null && !s.data().isEmpty()) {
                config.put("data", s.data());
                String schema = outputSchemaOf(s.data());
                if (schema != null) {
                    config.put("jsonOutputSchema", schema);
                }
            } else {
                config.put("data", Map.of("output", Map.of("type", "object")));
            }
            configByNode.put(nodeId, config);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", nodeId);
            node.put("type", "agent");
            // Short, clear name: the concise agentRole (e.g. "Report-Generator").
            node.put("title", s.agentRole());
            // Full instruction kept as node description for the execution prompt.
            node.put("description", s.instruction() != null && !s.instruction().isBlank()
                ? s.instruction() : s.agentRole());
            node.put("status", "pending");
            node.put("config", config);
            nodes.add(node);
        }

        // 3. Edges from the declared dependencies (JSON transport).
        for (GeneratedStep s : steps) {
            for (String dep : s.dependsOn()) {
                edges.add(jsonEdge(roleToNodeId.get(dep), roleToNodeId.get(s.agentRole())));
            }
        }

        // 4. Join nodes: merge points (a step consuming >= 2 predecessors) and, when the workflow
        //    ends in parallel chains that never merge, a final aggregator over the terminals.
        addJoinNodes(nodes, edges, steps, roleToNodeId, maxStepTimeoutMs(steps));

        // 5. Steps with predecessors consume the predecessor JSON data via a userMessageTemplate
        //    ({{<predNodeId>.output}}) + jsonOutput, resolved by the WorkflowEngine.
        for (GeneratedStep s : steps) {
            String nodeId = roleToNodeId.get(s.agentRole());
            List<String> predNodeIds = edges.stream()
                .filter(e -> nodeId.equals(String.valueOf(e.get("target"))))
                .map(e -> String.valueOf(e.get("source")))
                .distinct()
                .toList();
            if (!predNodeIds.isEmpty()) {
                Map<String, Object> config = configByNode.get(nodeId);
                config.put("jsonOutput", true);
                config.put("userMessageTemplate", followUpPrompt(s, nodes, predNodeIds));
            }
        }

        String now = Instant.now().toString();
        return new ApiDtos.WorkflowDef(id, name, nodes, edges, initialTask, now, now);
    }

    /**
     * Creates and persists a dedicated agent for one generated step. The agent is stored
     * immediately ({@code active=false}: it stays invisible in the classic agents/workflow page,
     * but the generated workflow can reference and execute it at any time).
     */
    private String createStepAgent(GeneratedStep step) {
        String id = "agent-" + UUID.randomUUID().toString().substring(0, 8);
        ApiDtos.AgentDefinition def = new ApiDtos.AgentDefinition(
            id,
            step.agentRole(),
            step.instruction() != null ? step.instruction() : "",
            "wf-step",
            uiConfig.getModel(),
            0.0, 4096, 1.0,
            step.tools() != null ? step.tools().toArray(new String[0]) : new String[0],
            new String[0], new String[0], "ua",
            step.systemPrompt() != null && !step.systemPrompt().isBlank() ? step.systemPrompt() : null,
            null, false, null,
            false
        );
        state.putAgent(def);
        return id;
    }

    /**
     * Extrahiert das Output-JSON-Schema an generierten Steps from {@code data.output}.
     * Liefert nur at an "echten" Schema (mehr as nur {@code type}) an Wert, damit
     * the Engine es via {@code StructuredOutputConfig} as responseFormat erzwingen kann.
     */
    private String outputSchemaOf(Map<String, Object> data) {
        Object out = data.get("output");
        if (!(out instanceof Map<?, ?>) || ((Map<?, ?>) out).isEmpty()) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> outMap = (Map<String, Object>) out;
        if (outMap.size() <= 1) return null;
        try {
            return MAPPER.writeValueAsString(outMap);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Inserts {@code join} nodes into the generated DAG so parallel branches are merged:
     * <ol>
     *   <li><b>Merge points:</b> every step node with ≥ 2 distinct predecessors is preceded by a
     *       join aggregating those predecessors (the step then consumes ONE aggregated input).</li>
     *   <li><b>Parallel start / divergence:</b> if the workflow still ends in ≥ 2 terminal nodes
     *       (parallel chains that never merge), a final join aggregates the terminas so the run
     *       waits for all parallel results.</li>
     * </ol>
     */
    void addJoinNodes(List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
                      List<GeneratedStep> steps, Map<String, String> roleToNodeId, long timeoutMs) {
        Set<String> existingIds = new HashSet<>();
        for (Map<String, Object> n : nodes) existingIds.add(String.valueOf(n.get("id")));
        int joinSeq = 0;

        // 1. Merge points: >= 2 distinct direct predecessors.
        for (GeneratedStep s : steps) {
            String target = roleToNodeId.get(s.agentRole());
            List<String> preds = incomingNodeIds(target, edges).stream().distinct().toList();
            if (preds.size() < 2) continue;
            String joinId = uniqueJoinId(existingIds, ++joinSeq);
            existingIds.add(joinId);
            addJoinNode(nodes, joinId, "Join: " + titlesOf(nodes, preds), preds, timeoutMs);
            for (String p : preds) edges.add(jsonEdge(p, joinId));
            edges.removeIf(e -> target.equals(String.valueOf(e.get("target")))
                && preds.contains(String.valueOf(e.get("source"))));
            edges.add(jsonEdge(joinId, target));
        }

        // 2. Final join when parallel chains never merge (>= 2 terminal step nodes).
        List<String> terminals = terminals(nodes, edges, new HashSet<>(roleToNodeId.values()));
        if (terminals.size() >= 2) {
            String joinId = uniqueJoinId(existingIds, ++joinSeq);
            addJoinNode(nodes, joinId, "Join: " + titlesOf(nodes, terminals), terminals, timeoutMs);
            for (String t : terminals) edges.add(jsonEdge(t, joinId));
        }

        // 3. Fork points: a step with >= 2 outgoing edges but no downstream merge point.
        //    Skip if all fork targets are already terminal (terminal parallelism = no join needed,
        //    e.g. broadcasting to multiple recipients). Otherwise insert a join to aggregate.
        for (GeneratedStep s : steps) {
            String source = roleToNodeId.get(s.agentRole());
            List<String> targets = outgoingNodeIds(source, edges);
            if (targets.size() < 2) continue;
            boolean hasMerge = false;
            for (String t : targets) {
                if (incomingNodeIds(t, edges).size() >= 2) { hasMerge = true; break; }
            }
            if (hasMerge) continue;
            boolean allTerminal = targets.stream()
                .allMatch(t -> outgoingNodeIds(t, edges).isEmpty());
            if (allTerminal) continue;
            String joinId = uniqueJoinId(existingIds, ++joinSeq);
            existingIds.add(joinId);
            addJoinNode(nodes, joinId, "Join: " + titlesOf(nodes, targets), targets, timeoutMs);
            for (String t : targets) edges.add(jsonEdge(t, joinId));
        }
    }

    private void addJoinNode(List<Map<String, Object>> nodes, String id, String title,
                             List<String> resultIds, long timeoutMs) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("resultIds", new ArrayList<>(resultIds));
        config.put("timeout", timeoutMs);
        Map<String, Object> join = new LinkedHashMap<>();
        join.put("id", id);
        join.put("type", "join");
        join.put("title", title);
        join.put("description", "Aggregates the results of parallel branches: " + resultIds);
        join.put("status", "pending");
        join.put("config", config);
        nodes.add(join);
    }

    private String uniqueJoinId(Set<String> existingIds, int seq) {
        String id = seq == 1 ? "node-join" : "node-join-" + seq;
        int i = seq;
        while (existingIds.contains(id)) {
            i++;
            id = "node-join-" + i;
        }
        return id;
    }

    /** Step nodes with no outgoing edges (candidates for a final aggregator join). */
    private List<String> terminals(List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
                                   Set<String> stepNodeIds) {
        Set<String> sources = new HashSet<>();
        for (Map<String, Object> e : edges) sources.add(String.valueOf(e.get("source")));
        List<String> terminal = new ArrayList<>();
        for (String id : stepNodeIds) {
            if (!sources.contains(id)) terminal.add(id);
        }
        return terminal;
    }

    /** Ids of the nodes that feed directly into the given target (incoming edges). */
    private List<String> incomingNodeIds(String targetId, List<Map<String, Object>> edges) {
        List<String> preds = new ArrayList<>();
        for (Map<String, Object> e : edges) {
            if (targetId.equals(String.valueOf(e.get("target")))) {
                preds.add(String.valueOf(e.get("source")));
            }
        }
        return preds;
    }

    /** Ids of the nodes that a given source feeds directly into (outgoing edges). */
    private List<String> outgoingNodeIds(String sourceId, List<Map<String, Object>> edges) {
        List<String> targets = new ArrayList<>();
        for (Map<String, Object> e : edges) {
            if (sourceId.equals(String.valueOf(e.get("source")))) {
                targets.add(String.valueOf(e.get("target")));
            }
        }
        return targets;
    }

    private String titlesOf(List<Map<String, Object>> nodes, List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(nodeTitle(nodes, id));
        }
        return sb.toString();
    }

    private String nodeTitle(List<Map<String, Object>> nodes, String id) {
        for (Map<String, Object> n : nodes) {
            if (id.equals(String.valueOf(n.get("id")))) {
                Object t = n.get("title");
                return t != null ? String.valueOf(t) : id;
            }
        }
        return id;
    }

    /** An edge carrying the source output as structured JSON into the target. */
    private Map<String, Object> jsonEdge(String source, String target) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceNodeId", source);
        // JSON transport so the successor receives the predecessor data as structured JSON.
        input.put("format", "json");
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", source);
        e.put("target", target);
        e.put("input", input);
        e.put("joinPolicy", "all");
        return e;
    }

    private long maxStepTimeoutMs(List<GeneratedStep> steps) {
        int max = 120;
        for (GeneratedStep s : steps) max = Math.max(max, s.timeoutSeconds());
        return max * 1000L;
    }

    /**
     * Builds the follow-up user prompt for a step with predecessors. The prompt references the
     * predecessor JSON data via template variables ({{<predNodeId>.output}}), which the
     * {@code WorkflowEngine} resolves from the {@code buildTemplateVars} map.
     */
    private String followUpPrompt(GeneratedStep s, List<Map<String, Object>> nodes,
                                  List<String> predNodeIds) {
        StringBuilder tpl = new StringBuilder(s.instruction());
        tpl.append("\n\nProcess the following JSON data from the predecessor steps "
            + "and return the result according to the data definition.\n");
        for (String pid : predNodeIds) {
            tpl.append("\n[predecessor step '").append(nodeTitle(nodes, pid)).append("'] (JSON):\n")
               .append("{{").append(pid).append(".output}}");
        }
        tpl.append("\n\nAnswer only with a JSON object matching the data definition "
            + "of this step.");
        return tpl.toString();
    }

    /** Strips markdown code fences if the planner wrapped the JSON in ```json ... ```. */
    private String stripFences(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        // remove a leading ```json fence and a trailing ```
        t = t.replaceFirst("(?s)^```(?:json)?\\s*", "");
        t = t.replaceFirst("(?s)\\s*```\\s*$", "");
        // DeepSeek/compatible models sometimes ewith a bare 'json' token before the object
        // without enclosing backticks, e.g. "json\n{...}" instead of "```json\n{...}\n```".
        t = t.replaceFirst("(?s)^json\\s*(?=\\{)", "");
        return t;
    }

    /**
     * A single step in the generated task DAG, following the Workflow-Architect schema:
     * {@code agentRole}, {@code instruction}, {@code tools}, {@code dependsOn}, {@code timeoutSeconds}.
     */
    public record GeneratedStep(String agentRole, String instruction,
                                List<String> tools, List<String> dependsOn, int timeoutSeconds,
                                String systemPrompt, Map<String, Object> data) {}

    /** Server-side state for one interactive chat session (Variante B). */
    static final class ChatSession {
        final String id;
        final String name;
        final String task;
        final List<String> transcript = new ArrayList<>();
        List<GeneratedStep> steps;

        ChatSession(String id, String name, String task, List<GeneratedStep> steps) {
            this.id = id;
            this.name = name;
            this.task = task;
            this.steps = steps;
        }
    }

    /** Result descriptor returned to the call for one chat round. */
    public record ChatDesc(String sessionId, ApiDtos.WorkflowDef workflow, String reply) {}

    /** Parsed steps + workflow meta + the last raw (pre-parse) planner reply. */
    private record WorkbookResult(List<GeneratedStep> steps, String workflowName,
                                  String description, String raw) {}
}
