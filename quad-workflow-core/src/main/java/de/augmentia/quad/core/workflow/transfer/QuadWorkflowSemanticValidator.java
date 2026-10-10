package de.augmentia.quad.core.workflow.transfer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Semantic validation of a v2 workflow document beyond what JSON Schema can express:
 * unique ids, resolvable references, a single END, an acyclic {@code dependsOn} graph,
 * and the HITL rule that {@code requiresHumanApproval} is only allowed on agent nodes.
 *
 * <p>This is the single place for graph rules; the JSON Schema covers structure only.
 * The former kafka {@code WorkflowDefinitionValidator} (R1–R7) is the conceptual
 * predecessor and can delegate here.</p>
 */
public final class QuadWorkflowSemanticValidator {

    private QuadWorkflowSemanticValidator() {
    }

    /** @return empty list when the document is semantically valid. */
    public static List<String> validate(JsonNode v2) {
        List<String> errors = new ArrayList<>();
        if (v2 == null || !v2.isObject()) {
            errors.add("$: document is null or not an object");
            return errors;
        }
        Set<String> subWorkflowIds = subWorkflowIds(v2);
        for (JsonNode workflow : workflowsOf(v2)) {
            validateWorkflow(workflow, subWorkflowIds, errors);
        }
        return errors;
    }

    private static void validateWorkflow(JsonNode wf, Set<String> subWorkflowIds, List<String> errors) {
        String wfId = wf.path("id").asText("?");
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        int endCount = 0;

        for (JsonNode n : wf.path("nodes")) {
            String id = n.path("id").asText("");
            if (byId.putIfAbsent(id, n) != null) {
                errors.add(at(wfId, id) + ": duplicate node id");
            }
            if ("end".equals(n.path("type").asText())) {
                endCount++;
            }
        }

        if (endCount == 0) {
            errors.add(wfId + ": no END node");
        } else if (endCount > 1) {
            errors.add(wfId + ": multiple END nodes (" + endCount + ")");
        }

        for (JsonNode n : wf.path("nodes")) {
            String id = n.path("id").asText("");
            String type = n.path("type").asText("");

            for (String ref : references(n)) {
                if (ref == null || ref.isBlank()) continue;
                if (ref.equals(id)) {
                    errors.add(at(wfId, id) + ": self-reference");
                } else if (!byId.containsKey(ref)) {
                    errors.add(at(wfId, id) + ": unknown reference '" + ref + "'");
                }
            }

            if (n.path("requiresHumanApproval").asBoolean(false) && !"agent".equals(type)) {
                errors.add(at(wfId, id) + ": requiresHumanApproval is only allowed on agent nodes");
            }

            if ("subworkflow".equals(type)) {
                String wfRef = n.path("workflowRef").asText("");
                if (!subWorkflowIds.isEmpty() && !subWorkflowIds.contains(wfRef)) {
                    errors.add(at(wfId, id) + ": unknown workflowRef '" + wfRef + "'");
                }
            }
        }

        detectCycle(wfId, byId, errors);
    }

    /** All node ids this node points at (dependsOn, branch targets, loop body, etc.). */
    private static List<String> references(JsonNode node) {
        List<String> refs = new ArrayList<>();
        JsonNode deps = node.path("dependsOn");
        if (deps.isArray()) deps.forEach(d -> refs.add(d.asText()));
        JsonNode branches = node.path("branches");
        if (branches.isArray()) {
            for (JsonNode b : branches) {
                if (b.hasNonNull("target")) refs.add(b.get("target").asText());
                if (b.hasNonNull("firstStep")) refs.add(b.get("firstStep").asText());
            }
        }
        if (node.hasNonNull("defaultTarget")) refs.add(node.get("defaultTarget").asText());
        JsonNode body = node.path("bodySteps");
        if (body.isArray()) body.forEach(b -> refs.add(b.asText()));
        return refs;
    }

    private static void detectCycle(String wfId, Map<String, JsonNode> byId, List<String> errors) {
        Map<String, Integer> state = new LinkedHashMap<>(); // 0=unseen,1=on-stack,2=done
        for (String id : byId.keySet()) {
            if (dfs(id, byId, state)) {
                errors.add(wfId + ": cyclic dependsOn graph involving '" + id + "'");
                return;
            }
        }
    }

    private static boolean dfs(String id, Map<String, JsonNode> byId, Map<String, Integer> state) {
        Integer s = state.get(id);
        if (s != null && s == 1) return true;   // back edge
        if (s != null && s == 2) return false;
        state.put(id, 1);
        JsonNode node = byId.get(id);
        if (node != null) {
            JsonNode deps = node.path("dependsOn");
            if (deps.isArray()) {
                for (JsonNode d : deps) {
                    if (byId.containsKey(d.asText()) && dfs(d.asText(), byId, state)) {
                        return true;
                    }
                }
            }
        }
        state.put(id, 2);
        return false;
    }

    private static Set<String> subWorkflowIds(JsonNode doc) {
        Set<String> ids = new LinkedHashSet<>();
        if (doc.path("subWorkflows").isArray()) {
            for (JsonNode wf : doc.get("subWorkflows")) {
                if (wf.hasNonNull("id")) ids.add(wf.get("id").asText());
            }
        }
        return ids;
    }

    private static List<JsonNode> workflowsOf(JsonNode doc) {
        List<JsonNode> list = new ArrayList<>();
        if (doc.path("mainWorkflow").isObject()) list.add(doc.get("mainWorkflow"));
        if (doc.path("subWorkflows").isArray()) {
            for (JsonNode wf : doc.get("subWorkflows")) {
                if (wf.isObject()) list.add(wf);
            }
        }
        return list;
    }

    private static String at(String workflowId, String nodeId) {
        return workflowId + "/" + nodeId;
    }
}
