package de.augmentia.quad.core.workflow.transfer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic normalization of the two HITL mechanisms in v2:
 *
 * <ul>
 *   <li>an explicit {@code hitl} task node, and</li>
 *   <li>{@code requiresHumanApproval: true} on an {@code agent} node (syntactic sugar).</li>
 * </ul>
 *
 * Both map to the same runtime gate ({@code WAITING_HITL}); this class derives the effective
 * gates and their mechanism so a run-event state machine has exactly one source of truth.
 * An explicit {@code hitl} node always wins; a flag on an agent node never coexists with a
 * separate {@code hitl} node for the same step (there is no such duplicate in the model —
 * the gate is attached to the node id).
 */
public final class QuadWorkflowHitlNormalizer {

    public enum Mechanism {
        /** Explicit {@code hitl} task node. */
        HITL_NODE,
        /** {@code requiresHumanApproval: true} on an agent node. */
        REQUIRES_HUMAN_APPROVAL
    }

    /** An effective approval gate: the step that must reach {@code WAITING_HITL}. */
    public record ApprovalGate(String nodeId, Mechanism mechanism, String assignee) {
    }

    private QuadWorkflowHitlNormalizer() {
    }

    /** All approval gates of a workflow, in node order. */
    public static List<ApprovalGate> approvalGates(JsonNode workflow) {
        List<ApprovalGate> gates = new ArrayList<>();
        for (JsonNode n : workflow.path("nodes")) {
            String type = n.path("type").asText("");
            if ("hitl".equals(type)) {
                gates.add(new ApprovalGate(n.path("id").asText(""), Mechanism.HITL_NODE,
                        n.hasNonNull("assignee") ? n.get("assignee").asText() : null));
            } else if ("agent".equals(type) && n.path("requiresHumanApproval").asBoolean(false)) {
                gates.add(new ApprovalGate(n.path("id").asText(""),
                        Mechanism.REQUIRES_HUMAN_APPROVAL, null));
            }
        }
        return gates;
    }

    /** @return the mechanism that makes this node an approval gate, or {@code null}. */
    public static Mechanism mechanismOf(JsonNode node) {
        String type = node.path("type").asText("");
        if ("hitl".equals(type)) return Mechanism.HITL_NODE;
        if ("agent".equals(type) && node.path("requiresHumanApproval").asBoolean(false)) {
            return Mechanism.REQUIRES_HUMAN_APPROVAL;
        }
        return null;
    }

    /** @return {@code true} if the given node is an approval gate. */
    public static boolean isApprovalGate(JsonNode node) {
        return mechanismOf(node) != null;
    }
}
