package de.augmentia.quad.quarkus.workflow.node.executor;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.memory.SessionMemory;
import de.augmentia.quad.quarkus.ui.SharedState;
import de.augmentia.quad.quarkus.contract.ApiDtos;
import de.augmentia.quad.quarkus.workflow.internal.GraphUtils;
import de.augmentia.quad.quarkus.workflow.internal.WorkflowExecutionContext;
import de.augmentia.quad.quarkus.workflow.node.NodeConfig;
import de.augmentia.quad.quarkus.workflow.node.NodeExecutor;
import de.augmentia.quad.quarkus.workflow.node.NodeRunner;
import de.augmentia.quad.quarkus.workflow.internal.RunStateWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nested workflow node. Invokes another workflow defined in {@code SharedState} and
 * executes its nodes linearly in its own execution state. The result output is
 * aggregated according to {@code outputMapping}.
 */
@ApplicationScoped
public class NestedWorkflowNodeExecutor implements NodeExecutor {

    private static final Logger log = Logger.getLogger(NestedWorkflowNodeExecutor.class);

    @Inject SharedState state;
    @Inject RunStateWriter runWriter;

    @Override
    public Set<String> getTypes() {
        return Set.of("nested-workflow");
    }

    @Override
    public boolean execute(Map<String, Object> node, List<Map<String, Object>> allNodes,
                           List<Map<String, Object>> edges, WorkflowExecutionContext ctx, NodeRunner runner) {
        String nodeId = NodeConfig.id(node);
        String workflowId = NodeConfig.str(node, "workflowId", "");
        if (workflowId.isBlank()) {
            String hint = "nested-workflow: no workflowId configured";
            ctx.putOutput(nodeId, hint);
            runWriter.updateAndPersist(ctx.run(), nodeId, "completed", "", hint);
            return true;
        }
        ApiDtos.WorkflowDef sub = state.workflows().get(workflowId);
        if (sub == null) {
            ctx.putOutput(nodeId, "nested-workflow: workflow not found: " + workflowId);
            return true;
        }

        log.infof("Nested node %s invoking workflow %s", nodeId, workflowId);

        AgentSessionState subState = subState();
        List<Map<String, Object>> subOrdered = GraphUtils.topologicalSort(sub.nodes, sub.edges);

        String inputContext = ctx.initialData();
        WorkflowExecutionContext subCtx = new WorkflowExecutionContext(ctx.runId(), null, inputContext, inputContext, subState, false);
        for (var subNode : subOrdered) {
            runner.runNode(subNode, sub.nodes, sub.edges, subCtx, Set.of());
        }
        Map<String, String> subOutputs = subCtx.outputs();

        StringBuilder out = new StringBuilder();
        out.append("Nested workflow: ").append(sub.name)
            .append(" (").append(subOrdered.size()).append(" nodes)\n");

        Map<String, Object> config = NodeConfig.of(node);
        Object outputMapping = config.get("outputMapping");
        String lastSub = GraphUtils.lastOrEmpty(subOutputs);
        if (outputMapping instanceof Map<?, ?> mapping && !mapping.isEmpty()) {
            for (var en : mapping.entrySet()) {
                String subKey = String.valueOf(en.getKey());
                String parentKey = String.valueOf(en.getValue());
                String value = subOutputs.get(subKey);
                if (value == null) value = subOutputs.get("__last");
                if (value == null) value = lastSub;
                String mapped = (value == null || value.length() <= 4000)
                    ? value
                    : GraphUtils.firstLine(value);
                out.append(parentKey).append(" = ").append(mapped).append("\n");
            }
        } else {
            String lastKey = null;
            for (var en : subOutputs.entrySet()) {
                out.append("[").append(en.getKey()).append("] ").append(GraphUtils.firstLine(en.getValue())).append("\n");
                lastKey = en.getKey();
            }
            if (subOutputs.size() == 1 && lastKey != null) {
                subOutputs.put("__last", subOutputs.get(lastKey));
            }
        }
        String result = out.toString();
        ctx.putOutput(nodeId, result);
        runWriter.updateAndPersist(ctx.run(), nodeId, "completed", inputContext, result);
        return true;
    }

    private AgentSessionState subState() {
        AgentSessionState s = new AgentSessionState();
        s.setMemory(new SessionMemory(50));
        return s;
    }
}