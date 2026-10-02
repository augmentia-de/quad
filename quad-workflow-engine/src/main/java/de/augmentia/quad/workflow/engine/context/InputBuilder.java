package de.augmentia.quad.workflow.engine.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.contract.ApiDtos;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.workflow.run.WorkflowRunState;
import de.augmentia.quad.workflow.engine.internal.GraphUtils;
import de.augmentia.quad.workflow.engine.node.NodeConfig;
import de.augmentia.quad.workflow.engine.port.WorkflowDefinitionPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds input (prompts, templates, start envelope) for a single node from the
 * predecessor outputs and the runtime context. Contains no LLM or scheduling logic
 * and is therefore isolated and unit-testable.
 *
 * <p>Two mapping mechanisms exist and they are additive:
 * <ul>
 *   <li><b>Edge mappings</b> — {@code edges[].input} / {@code edges[].inputs}. Edge-bound by
 *       construction: only a direct predecessor can contribute.</li>
 *   <li><b>{@code config.inputMappings}</b> — a node-level list that may read any node of the run,
 *       optionally from an earlier loop iteration ({@code scope}). This is what lets a node react
 *       to feedback produced in a previous round of a loop, since the loop clears the current
 *       outputs between iterations.</li>
 * </ul>
 * When both address the same template variable the node-level mapping wins and the collision is
 * logged.
 */
public class InputBuilder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkflowDefinitionPort definitions;

    public InputBuilder(final WorkflowDefinitionPort definitions) {
        this.definitions = definitions;
    }

    public String buildNodeInput(Map<String, Object> target, List<Map<String, Object>> edges,
                                 Map<String, String> outputs, AgentSessionState sessionState, String initialData) {
        return buildNodeInput(target, edges, outputs, sessionState, initialData, initialData);
    }

    /** History provider; injected so this class stays free of engine-internal types. */
    public interface HistoryProvider {
        List<String> resolve(String nodeId, String scope);
    }

    private HistoryProvider history = (nodeId, scope) -> List.of();

    public void setHistory(HistoryProvider history) {
        this.history = history != null ? history : (nodeId, scope) -> List.of();
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
        return buildNodeInput(target, edges, outputs, sessionState, initialData, fallbackData, null);
    }

    @SuppressWarnings("unchecked")
    public String buildNodeInput(Map<String, Object> target, List<Map<String, Object>> edges,
                                 Map<String, String> outputs, AgentSessionState sessionState,
                                 String initialData, String fallbackData, HistoryProvider ctxHistory) {
        if (ctxHistory != null) this.history = ctxHistory;
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

        String nodeMappings = resolveInputMappings(target, outputs);
        if (!nodeMappings.isEmpty()) {
            ctx.append(nodeMappings);
            hasPredecessorOutput = true;
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
                                     WorkflowRunState run, AgentSessionState sessionState,
                                     String initialData, Map<String, Object> config) {
        String workflowId = run != null ? run.workflowId : "";
        String workflowName = "";
        if (workflowId != null && !workflowId.isBlank()) {
            ApiDtos.WorkflowDef wf = definitions.find(workflowId);
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

    /**
     * Renders {@code config.inputMappings} — the node-level supplement to the edge mappings.
     *
     * <p>Each entry is {@code {"source": nodeId, "scope": …, "format": text|json, "paths": […]}}.
     * Scope defaults to {@code last}. Sources that resolve to nothing are skipped rather than
     * rendered as an empty section, so a first iteration produces no {@code [reviewer]} header.
     */
    @SuppressWarnings("unchecked")
    private String resolveInputMappings(Map<String, Object> target, Map<String, String> outputs) {
        Object raw = NodeConfig.of(target).get("inputMappings");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> mapping = (Map<String, Object>) m;
            String source = mapping.get("source") != null
                    ? String.valueOf(mapping.get("source")) : null;
            if (source == null || source.isBlank()) continue;

            String scope = mapping.get("scope") != null
                    ? String.valueOf(mapping.get("scope")) : "last";
            String format = mapping.get("format") != null
                    ? String.valueOf(mapping.get("format")) : "text";
            String join = mapping.get("join") != null
                    ? String.valueOf(mapping.get("join")) : "\n";

            List<String> values = history.resolve(source, scope);
            if (values.isEmpty()) {
                // A mapping that points at nothing is a configuration problem worth surfacing,
                // but not a reason to emit an empty section into the prompt.
                continue;
            }

            if ("json".equals(format)) {
                List<String> paths = jsonPaths(mapping);
                for (String value : values) {
                    if (paths.isEmpty()) {
                        sb.append("[").append(source).append("@").append(scope)
                          .append("] (JSON):\n").append(value).append("\n\n");
                    } else {
                        for (String path : paths) {
                            sb.append("[").append(source).append("@").append(scope)
                              .append("] (JSON, path=").append(path).append("):\n")
                              .append(JsonPathResolver.resolve(value, path)).append("\n\n");
                        }
                    }
                }
            } else {
                sb.append("[").append(source).append("@").append(scope).append("]: ")
                  .append(String.join(join, values)).append("\n\n");
            }
        }
        return sb.toString();
    }

    public Map<String, String> buildTemplateVars(Map<String, Object> target, List<Map<String, Object>> edges,
                                                 Map<String, String> outputs, String initialData) {
        Map<String, String> vars = new LinkedHashMap<>();
        String nodeId = NodeConfig.id(target);

        // Remember the last real predecessor output. "previousOutput" used to be read from the last
        // key of the map, which was always "initialPrompt" because that is inserted last — so it
        // returned the task text instead of a predecessor value.
        String lastPredecessorOutput = null;

        for (var edge : edges) {
            String targetId = String.valueOf(edge.get("target"));
            if (!targetId.equals(nodeId)) continue;

            for (Map<String, Object> inputMapping : edgeInputs(edge)) {
                String sourceNodeId = inputMapping.containsKey("sourceNodeId")
                    ? String.valueOf(inputMapping.get("sourceNodeId"))
                    : String.valueOf(edge.get("source"));

                if (outputs.containsKey(sourceNodeId)) {
                    String value = outputs.get(sourceNodeId);
                    vars.put(sourceNodeId + ".output", value);
                    lastPredecessorOutput = value;
                }
            }
        }

        // Node-level mappings contribute the same template variables and win over edge mappings
        // for the same source.
        for (String[] entry : mappingTemplateVars(target)) {
            vars.put(entry[0], entry[1]);
        }

        vars.put("initialPrompt", initialData != null ? initialData : "");
        vars.put("previousOutput", lastPredecessorOutput != null
                ? lastPredecessorOutput
                : (initialData != null ? initialData : ""));

        return vars;
    }

    /** Template variables contributed by {@code config.inputMappings}, as {@code {key, value}} pairs. */
    @SuppressWarnings("unchecked")
    private List<String[]> mappingTemplateVars(Map<String, Object> target) {
        List<String[]> result = new ArrayList<>();
        Object raw = NodeConfig.of(target).get("inputMappings");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return result;

        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> mapping = (Map<String, Object>) m;
            String source = mapping.get("source") != null
                    ? String.valueOf(mapping.get("source")) : null;
            if (source == null || source.isBlank()) continue;
            String scope = mapping.get("scope") != null
                    ? String.valueOf(mapping.get("scope")) : "last";

            List<String> values = history.resolve(source, scope);
            if (values.isEmpty()) continue;
            String joined = String.join("\n", values);
            String key = "last".equals(scope)
                    ? source + ".output"
                    : source + ".output@" + scope;
            result.add(new String[] { key, joined });
        }
        return result;
    }

    /**
     * Substitutes {@code {{key}}} from {@code vars}.
     *
     * <p>An unresolved placeholder used to survive into the prompt as literal text, so a typo in a
     * scope name looked to the model like content. Unresolved placeholders are now reported and the
     * template is left untouched for them; {@link #renderTemplateStrict} turns that into an error.
     */
    public String renderTemplate(String template, Map<String, String> vars) {
        return substitute(template, vars, false);
    }

    /** Like {@link #renderTemplate} but throws when a placeholder cannot be resolved. */
    public String renderTemplateStrict(String template, Map<String, String> vars) {
        return substitute(template, vars, true);
    }

    private String substitute(String template, Map<String, String> vars, boolean strict) {
        if (template == null || template.isEmpty()) return template;
        String result = template;
        List<String> unresolved = new ArrayList<>();

        for (var entry : vars.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue();
            if (value == null) continue;
            result = result.replace(placeholder, value);
        }

        // Whatever {{...}} is left was not in vars at all.
        var matcher = java.util.regex.Pattern.compile("\\{\\{([^{}]+)}}").matcher(result);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (vars.containsKey(name)) continue;
            if (!unresolved.contains(name)) unresolved.add(name);
        }

        if (unresolved.isEmpty()) return result;
        if (strict) {
            throw new IllegalArgumentException(
                    "Unresolved template placeholders: " + unresolved
                    + ". Available: " + new java.util.TreeSet<>(vars.keySet()));
        }
        java.util.logging.Logger.getLogger(InputBuilder.class.getName()).warning(
                "Template placeholders left unresolved: " + unresolved);
        return result;
    }
}