package de.augmentia.quad.quarkus.ui;

import de.augmentia.quad.contract.ApiDtos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Materializes the internal step agents of a (dynamically generated) workflow.
 *
 * <p>A generated workflow references one dedicated agent per step, persisted with
 * {@code category="wf-step"} and {@code active=false} (hidden from the classic agent list).
 * This helper:</p>
 * <ul>
 *   <li><b>activates</b> them (visible + editable) — or <b>deactivates</b> them again, and</li>
 *   <li><b>mirrors</b> the node transport fields ({@code systemPrompt}, {@code jsonOutputSchema},
 *       {@code jsonOutput}, {@code userMessageTemplate}, {@code tools}) into the agent
 *       definition, so the agent editor and the workflow node do not diverge.</li>
 * </ul>
 *
 * <p>All operations are <b>idempotent</b> and only touch agents of category
 * {@value #STEP_CATEGORY}. Mirroring fills only <em>missing</em> agent fields — existing
 * values are never overwritten. The workflow node stays the execution/data-flow source of
 * truth; the mirrored values make the agent self-describing and editable.</p>
 */
public final class WorkflowAgentMaterializer {

    public static final String STEP_CATEGORY = "wf-step";

    /**
     * @param activated   referenced step agents switched to active
     * @param deactivated referenced step agents switched to inactive
     * @param mirrored    step agents whose definition was enriched from the node
     * @param agentIds    referenced step agents (deduplicated)
     */
    public record Result(int activated, int deactivated, int mirrored, List<String> agentIds) {
    }

    private WorkflowAgentMaterializer() {
    }

    /** Activates the referenced step agents so they become editable. */
    public static Result activate(ApiDtos.WorkflowDef workflow, SharedState state) {
        return apply(workflow, state, Boolean.TRUE);
    }

    /** Deactivates the referenced step agents (reverse of {@link #activate}). */
    public static Result deactivate(ApiDtos.WorkflowDef workflow, SharedState state) {
        return apply(workflow, state, Boolean.FALSE);
    }

    /** Mirrors node transport fields into the step agents without changing visibility. */
    public static Result mirror(ApiDtos.WorkflowDef workflow, SharedState state) {
        return apply(workflow, state, null);
    }

    private static Result apply(ApiDtos.WorkflowDef workflow, SharedState state, Boolean setActive) {
        List<String> agentIds = new ArrayList<>();
        int activated = 0;
        int deactivated = 0;
        int mirrored = 0;
        if (workflow == null || workflow.nodes == null) {
            return new Result(0, 0, 0, agentIds);
        }
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> node : workflow.nodes) {
            Object cfgObj = node.get("config");
            if (!(cfgObj instanceof Map<?, ?> cfg)) continue;
            Object rawAgentId = cfg.get("agentId");
            if (rawAgentId == null) continue;
            String agentId = String.valueOf(rawAgentId);
            if (!seen.add(agentId)) continue;

            ApiDtos.AgentDefinition def = state.agents().get(agentId);
            if (def == null || !STEP_CATEGORY.equals(def.category)) continue;

            agentIds.add(agentId);
            boolean dirty = mirrorInto(def, cfg);
            if (dirty) mirrored++;

            if (setActive != null && def.active != setActive) {
                def.active = setActive;
                if (setActive) activated++; else deactivated++;
                dirty = true;
            }

            if (dirty) {
                state.putAgent(def); // in-memory + persisted
            }
        }
        return new Result(activated, deactivated, mirrored, agentIds);
    }

    /**
     * Mirrors the node transport fields into the agent definition, <b>fill-only</b>
     * (existing agent values are never overwritten). Returns {@code true} if anything changed.
     * Does not persist — callers decide when to {@code state.putAgent(def)}.
     */
    public static boolean mirrorInto(ApiDtos.AgentDefinition def, Map<?, ?> cfg) {
        boolean dirty = false;
        if (isBlank(def.systemPrompt) && cfg.get("systemPrompt") instanceof String sp && !sp.isBlank()) {
            def.systemPrompt = sp;
            dirty = true;
        }
        if (isBlank(def.jsonOutputSchema)
                && cfg.get("jsonOutputSchema") instanceof String schema && !schema.isBlank()) {
            def.jsonOutputSchema = schema;
            dirty = true;
        }
        if (isBlank(def.userMessageTemplate)
                && cfg.get("userMessageTemplate") instanceof String umt && !umt.isBlank()) {
            def.userMessageTemplate = umt;
            dirty = true;
        }
        if (!def.jsonOutput && Boolean.TRUE.equals(cfg.get("jsonOutput"))) {
            def.jsonOutput = true;
            dirty = true;
        }
        if ((def.tools == null || def.tools.length == 0)
                && cfg.get("tools") instanceof List<?> tools && !tools.isEmpty()) {
            def.tools = tools.stream().map(String::valueOf).toArray(String[]::new);
            dirty = true;
        }
        return dirty;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
