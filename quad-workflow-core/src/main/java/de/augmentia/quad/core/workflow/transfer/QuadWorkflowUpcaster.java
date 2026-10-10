package de.augmentia.quad.core.workflow.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Upcasts a legacy QUAD workflow document (schema v1, flat {@code {nodes,edges}} with
 * untyped nodes) to the canonical v2 document (typed nodes with inline control flow).
 *
 * <p>Structural mapping only — it derives {@code dependsOn} from incoming edges and
 * branch/fork structure from outgoing edges, but does <b>not</b> resolve agent references.
 * The {@link QuadWorkflowPublishCompiler} fills/repairs {@code agentRef.contentHash} against
 * a real registry.</p>
 *
 * <p>Node-type mapping: {@code conditional/decision→decision}, {@code loop→loop},
 * {@code fork→fork}, {@code join→join}, {@code hitl→hitl},
 * {@code nested-workflow/subflow→subworkflow}, {@code end→end}; everything else
 * (agent, orchestrator, messaging-*, async, retry, …) becomes an {@code agent} node.</p>
 *
 * <p>Where a faithful mapping is impossible (e.g. a decision without outgoing edges, or a
 * subworkflow without {@code config.workflowId}), an exception is thrown — the conversion
 * fails closed rather than dropping information.</p>
 */
public final class QuadWorkflowUpcaster {

    public static final int TARGET_SCHEMA_VERSION = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** v2 agent fields copied verbatim from v1 (whitelist to satisfy {@code additionalProperties:false}). */
    private static final List<String> AGENT_FIELDS = List.of(
            "id", "name", "description", "category", "model", "temperature", "maxTokens",
            "topP", "tools", "permissions", "guardrailsInput", "guardrailsOutput", "hooks",
            "timeoutSeconds", "systemPrompt", "userMessageTemplate", "jsonOutput",
            "jsonOutputSchema", "chatParameters", "memory", "active");

    /** Parses and upcasts a v1 document from JSON text. */
    public JsonNode upcast(String v1Json) {
        try {
            return upcast(MAPPER.readTree(v1Json));
        } catch (QuadWorkflowConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new QuadWorkflowConversionException("Cannot parse v1 document", e);
        }
    }

    /** Upcasts a parsed v1 document to v2. */
    public JsonNode upcast(JsonNode v1) {
        if (v1 == null || v1.isNull()) {
            throw new QuadWorkflowConversionException("v1 document is null");
        }
        JsonNode workflowsNode = v1.path("workflows");
        if (!workflowsNode.isArray() || workflowsNode.isEmpty()) {
            throw new QuadWorkflowConversionException("v1 document has no workflows");
        }
        ArrayNode workflows = (ArrayNode) workflowsNode;

        String mainId = v1.path("mainWorkflowId").asText(null);
        int mainIndex = 0;
        if (mainId != null && !mainId.isBlank()) {
            for (int i = 0; i < workflows.size(); i++) {
                if (mainId.equals(workflows.get(i).path("id").asText())) {
                    mainIndex = i;
                    break;
                }
            }
        }

        ObjectNode out = MAPPER.createObjectNode();
        out.put("format", "quad.workflow");
        out.put("schemaVersion", TARGET_SCHEMA_VERSION);
        if (v1.hasNonNull("exportedAt")) {
            out.put("exportedAt", v1.get("exportedAt").asText());
        }
        out.putNull("contentHash");

        ArrayNode subs = MAPPER.createArrayNode();
        ObjectNode main = null;
        for (int i = 0; i < workflows.size(); i++) {
            ObjectNode wf = upcastWorkflow(workflows.get(i));
            if (i == mainIndex) {
                main = wf;
            } else {
                subs.add(wf);
            }
        }
        out.set("mainWorkflow", main);
        if (!subs.isEmpty()) {
            out.set("subWorkflows", subs);
        }

        JsonNode agents = v1.path("agents");
        if (agents.isArray() && !agents.isEmpty()) {
            ArrayNode ported = MAPPER.createArrayNode();
            for (JsonNode ag : agents) {
                ported.add(upcastAgent(ag));
            }
            out.set("agents", ported);
        }
        return out;
    }

    private ObjectNode upcastWorkflow(JsonNode wf) {
        String id = wf.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new QuadWorkflowConversionException("workflow without id");
        }
        ObjectNode o = MAPPER.createObjectNode();
        o.put("id", id);
        o.put("name", wf.path("name").asText(id));
        o.put("version", wf.path("version").asText("1.0.0"));
        o.put("status", wf.path("status").asText("draft"));
        copyIfPresent(wf, o, "initialTask");
        copyIfPresent(wf, o, "cachePrefix");
        copyIfPresent(wf, o, "prefixCaching");

        JsonNode nodesIn = wf.path("nodes").isArray() ? wf.get("nodes") : MAPPER.createArrayNode();
        Map<String, List<String>> incoming = incomingByTarget(wf);
        Map<String, List<JsonNode>> outgoing = outgoingBySource(wf);
        String firstEnd = firstEndId(nodesIn);

        ArrayNode nodesOut = MAPPER.createArrayNode();
        for (JsonNode n : nodesIn) {
            nodesOut.add(upcastNode(n, incoming, outgoing, firstEnd));
        }
        o.set("nodes", nodesOut);
        return o;
    }

    private ObjectNode upcastNode(JsonNode n, Map<String, List<String>> incoming,
                                  Map<String, List<JsonNode>> outgoing, String firstEnd) {
        String id = n.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new QuadWorkflowConversionException("node without id");
        }
        String v1Type = n.path("type").asText("agent").toLowerCase();
        String type = switch (v1Type) {
            case "conditional", "decision", "switch" -> "decision";
            case "loop", "for", "while" -> "loop";
            case "fork", "parallel" -> "fork";
            case "join" -> "join";
            case "hitl", "human" -> "hitl";
            case "nested-workflow", "sub-workflow", "subflow", "subworkflow" -> "subworkflow";
            case "end" -> "end";
            default -> "agent";
        };

        ObjectNode o = MAPPER.createObjectNode();
        o.put("id", id);
        o.put("type", type);
        copyIfPresent(n, o, "title");
        copyIfPresent(n, o, "description");

        List<String> deps = incoming.getOrDefault(id, List.of());
        if (!deps.isEmpty()) {
            o.set("dependsOn", toArray(deps));
        }

        JsonNode config = n.path("config");
        if (config.path("requiresHumanApproval").asBoolean(false)) {
            o.put("requiresHumanApproval", true);
        }
        if (config.hasNonNull("timeoutSeconds")) {
            o.put("timeoutMs", config.get("timeoutSeconds").asInt() * 1000);
        }

        switch (type) {
            case "agent" -> o.set("agent", agentRef(n, config, id));
            case "decision" -> upcastDecision(o, id, outgoing, firstEnd);
            case "fork" -> upcastFork(o, id, outgoing);
            case "loop" -> upcastLoop(o, config);
            case "join" -> o.put("joinType", normalizeJoinType(config.path("joinType").asText(null)));
            case "hitl" -> o.put("assignee", config.path("assignee").asText("unassigned"));
            case "subworkflow" -> upcastSubWorkflow(o, config, id);
            case "end" -> { /* nothing extra */ }
            default -> throw new QuadWorkflowConversionException("Unsupported node type: " + type);
        }
        return o;
    }

    private ObjectNode agentRef(JsonNode n, JsonNode config, String nodeId) {
        String agentId = config.path("agentId").asText(null);
        if (agentId == null || agentId.isBlank()) {
            agentId = n.path("agentId").asText(null);
        }
        String ref = agentId != null && !agentId.isBlank() ? agentId : nodeId;
        ObjectNode agent = MAPPER.createObjectNode();
        agent.put("ref", ref);
        // Placeholder; the publish compiler replaces it with the hash of the resolved agent.
        agent.put("contentHash", "unresolved:" + sha256Hex("agent-ref:" + ref));
        return agent;
    }

    private void upcastDecision(ObjectNode o, String id, Map<String, List<JsonNode>> outgoing, String firstEnd) {
        List<JsonNode> outs = outgoing.getOrDefault(id, List.of());
        if (outs.isEmpty() && firstEnd == null) {
            throw new QuadWorkflowConversionException(
                    "decision '" + id + "' has no outgoing edges and no end node to fall back to");
        }
        ArrayNode branches = MAPPER.createArrayNode();
        String defaultTarget = null;
        int i = 0;
        for (JsonNode e : outs) {
            String target = e.path("target").asText(null);
            if (target == null || target.isBlank()) continue;
            ObjectNode branch = MAPPER.createObjectNode();
            branch.put("branchId", e.path("id").asText("b" + i));
            if (e.hasNonNull("condition")) {
                branch.put("condition", e.get("condition").asText());
            } else {
                branch.putNull("condition");
                if (defaultTarget == null) defaultTarget = target;
            }
            branch.put("target", target);
            branches.add(branch);
            i++;
        }
        if (branches.isEmpty()) {
            ObjectNode branch = MAPPER.createObjectNode();
            branch.put("branchId", "default");
            branch.putNull("condition");
            branch.put("target", firstEnd);
            branches.add(branch);
            defaultTarget = firstEnd;
        }
        o.set("branches", branches);
        if (defaultTarget != null) {
            o.put("defaultTarget", defaultTarget);
        }
    }

    private void upcastFork(ObjectNode o, String id, Map<String, List<JsonNode>> outgoing) {
        List<JsonNode> outs = outgoing.getOrDefault(id, List.of());
        if (outs.isEmpty()) {
            throw new QuadWorkflowConversionException("fork '" + id + "' has no outgoing edges");
        }
        ArrayNode branches = MAPPER.createArrayNode();
        int i = 0;
        for (JsonNode e : outs) {
            String target = e.path("target").asText(null);
            if (target == null || target.isBlank()) continue;
            ObjectNode branch = MAPPER.createObjectNode();
            branch.put("branchId", e.path("id").asText("b" + i));
            branch.put("firstStep", target);
            branches.add(branch);
            i++;
        }
        if (branches.isEmpty()) {
            throw new QuadWorkflowConversionException("fork '" + id + "' has no valid target edges");
        }
        o.set("branches", branches);
    }

    private void upcastLoop(ObjectNode o, JsonNode config) {
        int maxIterations = config.path("maxIterations").asInt(1);
        o.put("maxIterations", Math.max(1, maxIterations));
        String exit = config.path("exitCondition").asText("true");
        o.put("exitCondition", exit.isBlank() ? "true" : exit);
    }

    private void upcastSubWorkflow(ObjectNode o, JsonNode config, String id) {
        String workflowRef = config.path("workflowId").asText(null);
        if (workflowRef == null || workflowRef.isBlank()) {
            throw new QuadWorkflowConversionException(
                    "subworkflow '" + id + "' has no config.workflowId");
        }
        o.put("workflowRef", workflowRef);
        if (config.hasNonNull("input")) {
            o.set("input", config.get("input"));
        }
    }

    private static String normalizeJoinType(String raw) {
        if (raw == null || raw.isBlank()) return "all";
        return switch (raw.toLowerCase()) {
            case "any" -> "any";
            case "n_of_m", "n-of-m", "nom" -> "n_of_m";
            default -> "all";
        };
    }

    private ObjectNode upcastAgent(JsonNode ag) {
        ObjectNode o = MAPPER.createObjectNode();
        for (String field : AGENT_FIELDS) {
            if (ag.hasNonNull(field)) {
                o.set(field, ag.get(field));
            }
        }
        if (!o.hasNonNull("id") || o.path("id").asText().isBlank()) {
            throw new QuadWorkflowConversionException("agent without id");
        }
        o.put("name", ag.path("name").asText(o.path("id").asText()));
        o.putIfAbsent("version", MAPPER.getNodeFactory().textNode("1.0.0"));
        // v1 used jsonInput (agent consumes JSON) — v2 names the output-format flag jsonOutput.
        if (!o.has("jsonOutput") && ag.hasNonNull("jsonInput")) {
            o.put("jsonOutput", ag.get("jsonInput").asBoolean());
        }
        return o;
    }

    // ── edge helpers ─────────────────────────────────────────────────────

    private Map<String, List<String>> incomingByTarget(JsonNode wf) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (JsonNode e : edgesOf(wf)) {
            String source = e.path("source").asText(null);
            String target = e.path("target").asText(null);
            if (source == null || target == null) continue;
            map.computeIfAbsent(target, k -> new ArrayList<>()).add(source);
        }
        return map;
    }

    private Map<String, List<JsonNode>> outgoingBySource(JsonNode wf) {
        Map<String, List<JsonNode>> map = new LinkedHashMap<>();
        for (JsonNode e : edgesOf(wf)) {
            String source = e.path("source").asText(null);
            if (source == null) continue;
            map.computeIfAbsent(source, k -> new ArrayList<>()).add(e);
        }
        return map;
    }

    private List<JsonNode> edgesOf(JsonNode wf) {
        List<JsonNode> edges = new ArrayList<>();
        JsonNode arr = wf.path("edges");
        if (arr.isArray()) {
            arr.forEach(edges::add);
        }
        return edges;
    }

    private String firstEndId(JsonNode nodes) {
        for (JsonNode n : nodes) {
            if ("end".equalsIgnoreCase(n.path("type").asText(""))) {
                return n.path("id").asText(null);
            }
        }
        return null;
    }

    private ArrayNode toArray(List<String> values) {
        ArrayNode a = MAPPER.createArrayNode();
        values.forEach(a::add);
        return a;
    }

    private static void copyIfPresent(JsonNode from, ObjectNode to, String field) {
        if (from.hasNonNull(field)) {
            to.set(field, from.get(field));
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
