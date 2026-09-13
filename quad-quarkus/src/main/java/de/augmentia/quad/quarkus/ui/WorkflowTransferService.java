package de.augmentia.quad.quarkus.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.augmentia.quad.core.workflow.transfer.QuadWorkflowSchemaValidator;
import de.augmentia.quad.quarkus.contract.ApiDtos;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Export/Import of workflows in the {@code quad.workflow.v1} envelope - mirror of the
 * Spring {@code WorkflowTransferService}. Import validates the payload deterministically
 * against {@code quad/schema/quad.workflow.v1.schema.json}, checks references
 * (agents + nested-workflow) and only then persists.
 *
 * <p>Strategies: {@code clone} (default) and {@code reuse}. write-through via
 * {@link SharedState} (operates on the same DB as the Spring backend).
 */
@ApplicationScoped
public class WorkflowTransferService {

    private static final String FORMAT = "quad.workflow";
    private static final int SCHEMA_VERSION = 1;
    private static final String IMPORT_SUFFIX = " (import)";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    SharedState state;

    /** Abbruch with HTTP-Status for Client. */
    public static class TransferException extends RuntimeException {
        public final int status;

        public TransferException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ── Export ──────────────────────────────────────────────

    public Map<String, Object> export(String workflowId) {
        ApiDtos.WorkflowDef main = state.workflows().get(workflowId);
        if (main == null) {
            throw new TransferException(404, "Workflow not found: " + workflowId);
        }

        List<ApiDtos.WorkflowDef> workflows = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        collectWorkflows(main, workflows, visited);

        Set<String> agentIds = new LinkedHashSet<>();
        for (ApiDtos.WorkflowDef wf : workflows) {
            collectAgentRefs(wf, agentIds);
        }
        List<Map<String, Object>> agents = new ArrayList<>();
        for (String aid : agentIds) {
            ApiDtos.AgentDefinition agent = state.agents().get(aid);
            if (agent == null) {
                throw new TransferException(422, "Export aborted: referenced agent '" + aid + "' does not exist");
            }
            agents.add(agent.toJson());
        }

        Map<String, Object> env = new LinkedHashMap<>();
        env.put("format", FORMAT);
        env.put("schemaVersion", SCHEMA_VERSION);
        env.put("exportedAt", Instant.now().toString());
        env.put("mainWorkflowId", workflowId);
        env.put("workflows", workflows.stream().map(this::workflowToMap).toList());
        env.put("agents", agents);
        return env;
    }

    private void collectWorkflows(ApiDtos.WorkflowDef wf, List<ApiDtos.WorkflowDef> out, Set<String> visited) {
        if (!visited.add(wf.id)) return;
        out.add(wf);
        for (Map<String, Object> node : wf.nodes == null ? List.<Map<String, Object>>of() : wf.nodes) {
            String ref = workflowRef(node);
            if (ref != null && !ref.isBlank()) {
                ApiDtos.WorkflowDef sub = state.workflows().get(ref);
                if (sub == null) {
                    throw new TransferException(422, "Export aborted: referenced workflow '" + ref
                        + "' (from node '" + node.get("id") + "') does not exist");
                }
                collectWorkflows(sub, out, visited);
            }
        }
    }

    private void collectAgentRefs(ApiDtos.WorkflowDef wf, Set<String> agentIds) {
        for (Map<String, Object> node : wf.nodes == null ? List.<Map<String, Object>>of() : wf.nodes) {
            String ref = agentRef(node);
            if (ref != null && !ref.isBlank()) {
                agentIds.add(ref);
            }
        }
    }

    private Map<String, Object> workflowToMap(ApiDtos.WorkflowDef wf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", wf.id);
        m.put("name", wf.name);
        m.put("nodes", wf.nodes);
        m.put("edges", wf.edges);
        m.put("initialTask", wf.initialTask);
        m.put("createdAt", wf.createdAt);
        m.put("updatedAt", wf.updatedAt);
        return m;
    }

    // ── Import ──────────────────────────────────────────────

    public Map<String, Object> importWorkflow(Object body, String strategy) {
        boolean clone = !"reuse".equalsIgnoreCase(strategy);
        ObjectNode root = toEnvelope(body);

        List<String> issues = QuadWorkflowSchemaValidator.validate(root);
        if (!issues.isEmpty()) {
            throw new TransferException(400, "Import rejected by workflow schema (" + issues.size() + " issue(s)): " + issues);
        }

        List<ApiDtos.WorkflowDef> wfs = parseWorkflows(root);
        Map<String, JsonNode> bundledAgents = parseAgents(root);
        String mainId = mainWorkflowId(root, wfs);

        Set<String> wfIds = wfs.stream().map(w -> w.id).collect(Collectors.toSet());
        List<String> missingWorkflows = new ArrayList<>();
        List<String> missingAgents = new ArrayList<>();
        for (ApiDtos.WorkflowDef wf : wfs) {
            for (Map<String, Object> node : wf.nodes == null ? List.<Map<String, Object>>of() : wf.nodes) {
                String wref = workflowRef(node);
                if (wref != null && !wref.isBlank() && !wfIds.contains(wref)) {
                    missingWorkflows.add(wref + " (node " + node.get("id") + ")");
                }
                String aref = agentRef(node);
                if (aref != null && !aref.isBlank()
                        && !bundledAgents.containsKey(aref)
                        && !state.agents().containsKey(aref)) {
                    missingAgents.add(aref + " (node " + node.get("id") + ")");
                }
            }
        }
        if (!missingWorkflows.isEmpty() || !missingAgents.isEmpty()) {
            throw new TransferException(422, "Import rejected, unresolved references"
                + (missingWorkflows.isEmpty() ? "" : " — missing sub-workflows: " + missingWorkflows)
                + (missingAgents.isEmpty() ? "" : " — missing agents: " + missingAgents));
        }

        return clone ? importClone(wfs, bundledAgents, mainId) : importReuse(wfs, bundledAgents, mainId);
    }

    private ObjectNode toEnvelope(Object body) {
        ObjectNode root = MAPPER.valueToTree(body);
        boolean isSingleWorkflow = root.has("nodes") && !root.has("format");
        if (isSingleWorkflow) {
            String id = root.path("id").isMissingNode() ? null : root.path("id").asText();
            ObjectNode env = MAPPER.createObjectNode();
            env.put("format", FORMAT);
            env.put("schemaVersion", SCHEMA_VERSION);
            if (id != null && !id.isBlank()) env.put("mainWorkflowId", id);
            ObjectNode wrapped = root.deepCopy();
            env.set("workflows", MAPPER.createArrayNode().add(wrapped));
            env.set("agents", MAPPER.createArrayNode());
            root = env;
        }
        return root;
    }

    private List<ApiDtos.WorkflowDef> parseWorkflows(ObjectNode root) {
        List<ApiDtos.WorkflowDef> wfs = new ArrayList<>();
        for (JsonNode w : root.path("workflows")) {
            String id = w.path("id").asText();
            String name = w.path("name").asText("");
            List<Map<String, Object>> nodes = toMaps(w.path("nodes"));
            List<Map<String, Object>> edges = toMaps(w.path("edges"));
            String init = w.path("initialTask").isNull() ? null : w.path("initialTask").asText();
            String now = Instant.now().toString();
            wfs.add(new ApiDtos.WorkflowDef(id, name, nodes, edges, init, now, now));
        }
        return wfs;
    }

    private Map<String, JsonNode> parseAgents(ObjectNode root) {
        Map<String, JsonNode> agents = new LinkedHashMap<>();
        for (JsonNode a : root.path("agents")) {
            if (a.has("id")) {
                agents.put(a.path("id").asText(), a);
            }
        }
        return agents;
    }

    private String mainWorkflowId(ObjectNode root, List<ApiDtos.WorkflowDef> wfs) {
        String main = root.path("mainWorkflowId").asText();
        if (main == null || main.isBlank() || wfs.isEmpty()) {
            return wfs.isEmpty() ? null : wfs.get(0).id;
        }
        return main;
    }

    private Map<String, Object> importReuse(List<ApiDtos.WorkflowDef> wfs,
            Map<String, JsonNode> bundledAgents, String mainId) {
        List<String> conflicts = wfs.stream()
            .filter(w -> state.workflows().containsKey(w.id))
            .map(w -> w.id)
            .toList();
        if (!conflicts.isEmpty()) {
            throw new TransferException(409, "Workflow ids already exist: " + conflicts);
        }
        List<String> created = new ArrayList<>();
        List<String> reused = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : bundledAgents.entrySet()) {
            if (state.agents().containsKey(e.getKey())) {
                reused.add(e.getKey());
            } else {
                state.putAgent(toAgentDefinition(e.getKey(), e.getValue(), null));
                created.add(e.getKey());
            }
        }
        saveAllWorkflows(wfs, Map.of(), Map.of(), mainId, false);
        return result(mainId, wfs.stream().map(w -> w.id).toList(), created, reused, Map.of(), Map.of());
    }

    private Map<String, Object> importClone(List<ApiDtos.WorkflowDef> wfs,
            Map<String, JsonNode> bundledAgents, String mainId) {
        Map<String, String> wfIdMap = new LinkedHashMap<>();
        for (ApiDtos.WorkflowDef wf : wfs) {
            wfIdMap.put(wf.id, "workflow-" + uuid8());
        }
        Map<String, String> agentIdMap = new LinkedHashMap<>();
        for (String aid : bundledAgents.keySet()) {
            agentIdMap.put(aid, "agent-" + uuid8());
        }
        List<String> created = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : bundledAgents.entrySet()) {
            String newId = agentIdMap.get(e.getKey());
            String name = e.getValue().path("name").isTextual() ? e.getValue().path("name").asText() : null;
            String newName = name != null && !name.endsWith(IMPORT_SUFFIX) ? name + IMPORT_SUFFIX : name;
            state.putAgent(toAgentDefinition(newId, e.getValue(), newName));
            created.add(newId);
        }

        saveAllWorkflows(wfs, wfIdMap, agentIdMap, mainId, true);
        return result(wfIdMap.getOrDefault(mainId, mainId),
            wfs.stream().map(w -> wfIdMap.get(w.id)).toList(),
            created, List.of(), new LinkedHashMap<>(wfIdMap), new LinkedHashMap<>(agentIdMap));
    }

    private void saveAllWorkflows(List<ApiDtos.WorkflowDef> wfs,
            Map<String, String> wfIdMap, Map<String, String> agentIdMap, String mainId, boolean clone) {
        List<ApiDtos.WorkflowDef> ordered = new ArrayList<>();
        for (ApiDtos.WorkflowDef wf : wfs) {
            if (!wf.id.equals(mainId)) ordered.add(wf);
        }
        for (ApiDtos.WorkflowDef wf : wfs) {
            if (wf.id.equals(mainId)) ordered.add(wf);
        }

        Map<String, Map<String, String>> nodeMaps = new LinkedHashMap<>();
        if (clone) {
            for (ApiDtos.WorkflowDef wf : ordered) {
                Map<String, String> nm = new LinkedHashMap<>();
                for (Map<String, Object> node : wf.nodes == null ? List.<Map<String, Object>>of() : wf.nodes) {
                    nm.put(String.valueOf(node.get("id")), "node-" + uuid8());
                }
                nodeMaps.put(wf.id, nm);
            }
        }

        String now = Instant.now().toString();
        for (ApiDtos.WorkflowDef wf : ordered) {
            String newId = clone ? wfIdMap.getOrDefault(wf.id, wf.id) : wf.id;
            List<Map<String, Object>> nodes = clone ? remapNodes(wf, nodeMaps, wfIdMap, agentIdMap) : wf.nodes;
            List<Map<String, Object>> edges = clone ? remapEdges(wf.edges, nodeMaps.get(wf.id)) : wf.edges;
            String name = clone ? suffixImport(wf.name) : wf.name;
            state.putWorkflow(new ApiDtos.WorkflowDef(newId, name, nodes, edges, wf.initialTask, now, now));
        }
    }

    private List<Map<String, Object>> remapNodes(ApiDtos.WorkflowDef wf,
            Map<String, Map<String, String>> nodeMaps, Map<String, String> wfIdMap, Map<String, String> agentIdMap) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, String> localNodeIds = nodeMaps.get(wf.id);
        for (Map<String, Object> node : wf.nodes == null ? List.<Map<String, Object>>of() : wf.nodes) {
            Map<String, Object> copy = new LinkedHashMap<>(node);
            String oldNodeId = String.valueOf(copy.get("id"));
            String oldWfRef = workflowRef(node);
            copy.put("id", localNodeIds.getOrDefault(oldNodeId, oldNodeId));
            Object configObj = node.get("config");
            if (configObj instanceof Map<?, ?> cm) {
                Map<String, Object> cfg = new LinkedHashMap<>();
                for (Map.Entry<?, ?> ce : cm.entrySet()) cfg.put(String.valueOf(ce.getKey()), ce.getValue());
                if (cfg.containsKey("agentId")) cfg.put("agentId", agentIdMap.getOrDefault(String.valueOf(cfg.get("agentId")), String.valueOf(cfg.get("agentId"))));
                if (cfg.containsKey("workflowId")) cfg.put("workflowId", wfIdMap.getOrDefault(String.valueOf(cfg.get("workflowId")), String.valueOf(cfg.get("workflowId"))));
                if (oldWfRef != null && cfg.get("outputMapping") instanceof Map<?, ?> om) {
                    Map<String, String> subNodeIds = nodeMaps.getOrDefault(oldWfRef, Map.of());
                    Map<String, Object> mapped = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> me : om.entrySet()) {
                        mapped.put(subNodeIds.getOrDefault(String.valueOf(me.getKey()), String.valueOf(me.getKey())), me.getValue());
                    }
                    cfg.put("outputMapping", mapped);
                }
                copy.put("config", cfg);
            }
            Object propsObj = node.get("properties");
            if (propsObj instanceof Map<?, ?> pm) {
                Map<String, Object> props = new LinkedHashMap<>();
                for (Map.Entry<?, ?> pe : pm.entrySet()) props.put(String.valueOf(pe.getKey()), pe.getValue());
                if (props.containsKey("agentId")) props.put("agentId", agentIdMap.getOrDefault(String.valueOf(props.get("agentId")), String.valueOf(props.get("agentId"))));
                if (props.containsKey("workflowId")) props.put("workflowId", wfIdMap.getOrDefault(String.valueOf(props.get("workflowId")), String.valueOf(props.get("workflowId"))));
                copy.put("properties", props);
            }
            out.add(copy);
        }
        return out;
    }

    private List<Map<String, Object>> remapEdges(List<Map<String, Object>> edges, Map<String, String> nodeIds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> edge : edges == null ? List.<Map<String, Object>>of() : edges) {
            Map<String, Object> copy = new LinkedHashMap<>(edge);
            copy.put("id", "edge-" + uuid8());
            for (String key : List.of("source", "target")) {
                Object val = edge.get(key);
                if (val != null) {
                    copy.put(key, nodeIds.getOrDefault(String.valueOf(val), String.valueOf(val)));
                }
            }
            out.add(copy);
        }
        return out;
    }

    private String suffixImport(String name) {
        if (name == null || name.isBlank()) return "Untitled Workflow" + IMPORT_SUFFIX;
        return name.endsWith(IMPORT_SUFFIX) ? name : name + IMPORT_SUFFIX;
    }

    private Map<String, Object> result(String mainId, List<String> imported, List<String> created,
            List<String> reused, Map<String, String> wfRemap, Map<String, String> agentRemap) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mainWorkflowId", mainId);
        out.put("importedWorkflowIds", imported);
        out.put("createdAgentIds", created);
        out.put("reusedAgentIds", reused);
        out.put("remapWorkflowIds", wfRemap);
        out.put("remapAgentIds", agentRemap);
        return out;
    }

    // ── Referenz-Extraktion (beide Kontrakte) ────────────────

    private String workflowRef(Map<String, Object> node) {
        Object config = node.get("config");
        if (config instanceof Map<?, ?> c) {
            Object w = c.get("workflowId");
            if (w != null) return String.valueOf(w);
        }
        Object props = node.get("properties");
        if (props instanceof Map<?, ?> p) {
            Object w = p.get("workflowId");
            if (w != null) return String.valueOf(w);
        }
        return null;
    }

    private String agentRef(Map<String, Object> node) {
        Object config = node.get("config");
        if (config instanceof Map<?, ?> c) {
            Object a = c.get("agentId");
            if (a != null) return String.valueOf(a);
        }
        Object props = node.get("properties");
        if (props instanceof Map<?, ?> p) {
            Object a = p.get("agentId");
            if (a != null) return String.valueOf(a);
        }
        return null;
    }

    // ── Agent (JsonNode/Map → ApiDtos.AgentDefinition) ───────

    private ApiDtos.AgentDefinition toAgentDefinition(String id, JsonNode a, String nameOverride) {
        String name = nameOverride != null && !nameOverride.isBlank()
            ? nameOverride
            : a.path("name").isTextual() ? a.path("name").asText() : id;
        ApiDtos.AgentDefinition agent = new ApiDtos.AgentDefinition(
            id, name,
            textOrNull(a, "description"),
            a.path("category").asText("general"),
            a.path("model").asText("gpt-4o-mini"),
            a.path("temperature").asDouble(0.7),
            a.path("maxTokens").asInt(4096),
            a.path("topP").asDouble(1.0),
            stringArray(a, "tools"),
            stringArray(a, "guardrailsInput"),
            stringArray(a, "guardrailsOutput"),
            a.path("agentType").asText("ua"),
            textOrNull(a, "systemPrompt"),
            textOrNull(a, "userMessageTemplate"),
            a.path("jsonOutput").asBoolean(false),
            textOrNull(a, "chatParameters"),
            a.path("active").asBoolean(true),
            stringArray(a, "hooks")
        );
        agent.jsonOutputSchema = textOrNull(a, "jsonOutputSchema");
        return agent;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String[] stringArray(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isArray()) return new String[0];
        String[] out = new String[v.size()];
        for (int i = 0; i < v.size(); i++) {
            out[i] = v.get(i).asText();
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toMaps(JsonNode arr) {
        if (arr == null || !arr.isArray()) return List.of();
        try {
            return MAPPER.convertValue(arr, List.class);
        } catch (Exception e) {
            throw new TransferException(400, "Cannot parse workflow nodes/edges: " + e.getMessage());
        }
    }

    private static String uuid8() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}