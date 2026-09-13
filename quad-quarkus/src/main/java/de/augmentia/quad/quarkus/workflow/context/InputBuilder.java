package de.augmentia.quad.quarkus.workflow.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.ui.SharedState;
import de.augmentia.quad.quarkus.contract.ApiDtos;
import de.augmentia.quad.quarkus.persistence.RunStore;
import de.augmentia.quad.quarkus.workflow.internal.GraphUtils;
import de.augmentia.quad.quarkus.workflow.node.NodeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds input (prompts, templates, start envelope) for a single node from the
 * predecessor outputs and the runtime context. Contains no LLM or scheduling logic
 * and is therefore isolated and unit-testable.
 */
@ApplicationScoped
public class InputBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject SharedState state;

    public InputBuilder() {
    }

    public String buildNodeInput(Map<String, Object> target, List<Map<String, Object>> edges,
                                 Map<String, String> outputs, AgentSessionState sessionState, String initialData) {
        return buildNodeInput(target, edges, outputs, sessionState, initialData, initialData);
    }

/**
     * Returns all input mappings of edges. Supports both the legacy single
     * {@code input}-field and the new {@code inputs}-list (multiple sources per edge).
     * Falls back to the edge {@code source}-field if no mapping is set.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> edgeInputs(Map<String, Object> edge) {
        if (edge.get("inputs") instanceof List<?> list) {
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    result.add((Map<String, Object>) m);
                }
            }
            if (!result.isEmpty()) return result;
        }
        if (edge.get("input") instanceof Map<?, ?> m) {
            return List.of((Map<String, Object>) m);
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("sourceNodeId", String.valueOf(edge.get("source")));
        return List.of(fallback);
    }

    /**
     * Returns the subtree paths of JSON input mappings. Prefers {@code paths} (list),
     * falls back to the single {@code path}-field and otherwise returns an empty list
     * (= entire output). Empty entries are filtered out.
     */
    @SuppressWarnings("unchecked")
    private List<String> jsonPaths(Map<String, Object> inputMapping) {
        if (inputMapping.get("paths") instanceof List<?> raw) {
            List<String> result = new java.util.ArrayList<>();
            for (Object o : raw) {
                if (o == null) continue;
                String p = String.valueOf(o).trim();
                if (!p.isBlank()) result.add(p);
            }
            if (!result.isEmpty()) return result;
        }
        Object single = inputMapping.get("path");
        if (single != null) {
            String p = String.valueOf(single).trim();
            if (!p.isBlank()) return List.of(p);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public String buildNodeInput(Map<String, Object> target, List<Map<String, Object>> edges,
                                 Map<String, String> outputs, AgentSessionState sessionState,
                                 String initialData, String fallbackData) {
        String nodeId = NodeConfig.id(target);
        StringBuilder ctx = new StringBuilder();

        boolean hasPredecessorOutput = false;
        for (var edge : edges) {
            String targetId = String.valueOf(edge.get("target"));
            if (!targetId.equals(nodeId)) continue;

            for (Map<String, Object> inputMapping : edgeInputs(edge)) {
                String sourceNodeId = inputMapping.containsKey("sourceNodeId")
                    ? String.valueOf(inputMapping.get("sourceNodeId"))
                    : String.valueOf(edge.get("source"));
                String format = inputMapping.containsKey("format")
                    ? String.valueOf(inputMapping.get("format"))
                    : "text";

                if (!outputs.containsKey(sourceNodeId)) continue;

                String output = outputs.get(sourceNodeId);
                if ("json".equals(format)) {
                    List<String> paths = jsonPaths(inputMapping);
                    if (paths.isEmpty()) {
                        ctx.append("[").append(sourceNodeId).append("] (JSON):\n")
                           .append(output).append("\n\n");
                    } else {
                        for (String path : paths) {
                            ctx.append("[").append(sourceNodeId).append("] (JSON, path=").append(path).append("):\n")
                               .append(JsonPathResolver.resolve(output, path)).append("\n\n");
                        }
                    }
                } else {
                    ctx.append("[").append(sourceNodeId).append("]: ")
                       .append(output).append("\n\n");
                }
                hasPredecessorOutput = true;
            }
        }

        if (!hasPredecessorOutput && fallbackData != null && !fallbackData.isBlank()) {
            ctx.append("[Initial Data]: ").append(fallbackData).append("\n\n");
        } else if (!hasPredecessorOutput && initialData != null && !initialData.isBlank()) {
            ctx.append("[Initial Data]: ").append(initialData).append("\n\n");
        }

        if (sessionState != null && sessionState.memory() != null && sessionState.memory().hasSummary()) {
            ctx.append("[Session]: ").append(GraphUtils.truncate(sessionState.memory().summary(), 300)).append("\n\n");
        }

        Map<String, Object> config = NodeConfig.of(target);
        if (config.containsKey("context")) {
            ctx.append("[Context]: ").append(config.get("context")).append("\n\n");
        }

        return ctx.toString();
    }

    public String getPredecessorOutput(Map<String, Object> target, List<Map<String, Object>> edges,
                                       Map<String, String> outputs) {
        String nodeId = NodeConfig.id(target);
        for (var edge : edges) {
            String targetId = String.valueOf(edge.get("target"));
            if (targetId.equals(nodeId)) {
                String sourceId = String.valueOf(edge.get("source"));
                if (outputs.containsKey(sourceId)) {
                    return outputs.get(sourceId);
                }
            }
        }
        return "";
    }

    public String buildNodePrompt(Map<String, Object> node, String inputContext) {
        return buildNodePrompt(node, inputContext, false);
    }

    public String buildNodePrompt(Map<String, Object> node, String inputContext, boolean startNode) {
        String title = node.get("title") != null ? String.valueOf(node.get("title")) : "";
        String description = node.get("description") != null ? String.valueOf(node.get("description")) : "";
        Map<String, Object> config = NodeConfig.of(node);

        if (config.containsKey("prompt") && config.get("prompt") instanceof String override && !override.isBlank()) {
            StringBuilder prompt = new StringBuilder(override);
            if (!inputContext.isEmpty()) prompt.append("\n\nInput:\n").append(inputContext);
            return prompt.toString();
        }

        StringBuilder prompt = new StringBuilder();
        if (startNode) {
            if (!title.isEmpty()) prompt.append("Step: ").append(title).append("\n");
        } else if (!title.isEmpty()) {
            prompt.append("Task: ").append(title).append("\n");
        }
        if (!description.isEmpty()) prompt.append("Description: ").append(description).append("\n");
        if (!inputContext.isEmpty()) prompt.append("\nInput:\n").append(inputContext);

        if (config.containsKey("maxTokens")) {
            prompt.append("\n(Max output tokens: ").append(config.get("maxTokens")).append(")");
        }

        return prompt.toString();
    }

    /**
     * Composes the structured JSON envelope for the workflow start node so the first agent
     * receives a clear, machine-readable input even for plain-text start data:
     * {@code {workflow:{id,name}, node:{id,title}, task, input:{initialData[, sessionSummary, context]}}}.
     */
    public String buildStartEnvelope(Map<String, Object> node, String nodeId, String title,
                                     RunStore.RunState run, AgentSessionState sessionState,
                                     String initialData, Map<String, Object> config) {
        String workflowId = run != null ? run.workflowId : "";
        String workflowName = "";
        if (workflowId != null && !workflowId.isBlank()) {
            ApiDtos.WorkflowDef wf = state.workflows().get(workflowId);
            if (wf != null) workflowName = wf.name;
        }
        Map<String, Object> wf = new LinkedHashMap<>();
        wf.put("id", workflowId);
        wf.put("name", workflowName != null ? workflowName : "");
        Map<String, Object> nodeInfo = new LinkedHashMap<>();
        nodeInfo.put("id", nodeId);
        nodeInfo.put("title", title != null ? title : "");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("initialData", initialData != null ? initialData : "");
        if (sessionState != null && sessionState.memory() != null && sessionState.memory().hasSummary()) {
            input.put("sessionSummary", GraphUtils.truncate(sessionState.memory().summary(), 300));
        }
        if (config != null && config.containsKey("context")) {
            input.put("context", config.get("context"));
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("workflow", wf);
        envelope.put("node", nodeInfo);
        envelope.put("task", initialData != null ? initialData : "");
        envelope.put("input", input);
        try {
            return JSON.writeValueAsString(envelope);
        } catch (Exception e) {
            return "[Workflow start]\nTask: " + (initialData != null ? initialData : "");
        }
    }

    public Map<String, String> buildTemplateVars(Map<String, Object> target, List<Map<String, Object>> edges,
                                                 Map<String, String> outputs, String initialData) {
        Map<String, String> vars = new LinkedHashMap<>();
        String nodeId = NodeConfig.id(target);

        for (var edge : edges) {
            String targetId = String.valueOf(edge.get("target"));
            if (!targetId.equals(nodeId)) continue;

            for (Map<String, Object> inputMapping : edgeInputs(edge)) {
                String sourceNodeId = inputMapping.containsKey("sourceNodeId")
                    ? String.valueOf(inputMapping.get("sourceNodeId"))
                    : String.valueOf(edge.get("source"));

                if (outputs.containsKey(sourceNodeId)) {
                    vars.put(sourceNodeId + ".output", outputs.get(sourceNodeId));
                }
            }
        }

        vars.put("initialPrompt", initialData != null ? initialData : "");
        if (!vars.isEmpty()) {
            String lastKey = null;
            for (String k : vars.keySet()) lastKey = k;
            vars.put("previousOutput", vars.getOrDefault(lastKey, ""));
        }

        return vars;
    }

    public String renderTemplate(String template, Map<String, String> vars) {
        String result = template;
        for (var entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}