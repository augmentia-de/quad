package de.augmentia.quad.quarkus.workflow.node.executor;

import de.augmentia.quad.quarkus.workflow.internal.RunStateWriter;
import de.augmentia.quad.quarkus.workflow.internal.WorkflowExecutionContext;
import de.augmentia.quad.quarkus.workflow.node.NodeConfig;
import de.augmentia.quad.quarkus.workflow.node.NodeExecutor;
import de.augmentia.quad.quarkus.workflow.node.NodeRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit parallel-branching marker. The scheduler lets multiple outgoing branches
 * run concurrently; this node merely writes a marker as its output.
 */
@ApplicationScoped
public class ForkNodeExecutor implements NodeExecutor {

    private static final Logger log = Logger.getLogger(ForkNodeExecutor.class);

    @Inject
    RunStateWriter runWriter;

    @Override
    public Set<String> getTypes() {
        return Set.of("fork");
    }

    @Override
    public boolean execute(Map<String, Object> node, List<Map<String, Object>> allNodes,
                           List<Map<String, Object>> edges, WorkflowExecutionContext ctx, NodeRunner runner) {
        String nodeId = NodeConfig.id(node);
        ctx.putOutput(nodeId, "[fork]");
        runWriter.updateAndPersist(ctx.run(), nodeId, "completed", "", "[fork]");
        List<String> succs = new ArrayList<>();
        for (var edge : edges) {
            if (nodeId.equals(edge.get("source"))) succs.add(String.valueOf(edge.get("target")));
        }
        log.infof("══ WORKFLOW ⑃ [%s] FORK %s — parallel branches opened (successors: %s) ══",
            ctx.runId(), nodeId, succs);
        return true;
    }
}