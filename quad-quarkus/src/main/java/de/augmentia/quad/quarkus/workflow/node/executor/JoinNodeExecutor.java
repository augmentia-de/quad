package de.augmentia.quad.quarkus.workflow.node.executor;

import de.augmentia.quad.quarkus.workflow.internal.GraphUtils;
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
 * Join node. Aggregates the expected predecessor results ({@code resultIds}, default =
 * all incoming predecessors) into a common output that downstream aggregator agents consume.
 * On join timeout, missing results are marked.
 */
@ApplicationScoped
public class JoinNodeExecutor implements NodeExecutor {

    private static final Logger log = Logger.getLogger(JoinNodeExecutor.class);

    @Inject
    RunStateWriter runWriter;

    @Override
    public Set<String> getTypes() {
        return Set.of("join");
    }

    @Override
    public boolean execute(Map<String, Object> node, List<Map<String, Object>> allNodes,
                           List<Map<String, Object>> edges, WorkflowExecutionContext ctx, NodeRunner runner) {
        String nodeId = NodeConfig.id(node);
        List<String> expected = NodeConfig.joinResultIds(node);
        if (expected.isEmpty()) expected = GraphUtils.predecessorIds(nodeId, edges);

        StringBuilder sb = new StringBuilder();
        List<String> missing = new ArrayList<>();
        for (String s : expected) {
            if (ctx.hasOutput(s)) {
                sb.append("[").append(s).append("] ").append(ctx.output(s)).append("\n\n");
            } else {
                missing.add(s);
            }
        }
        if (!missing.isEmpty()) {
            sb.append("[join: timeout — missing results: ").append(String.join(", ", missing)).append("]\n\n");
        }
        String result = sb.toString().trim();
        if (result.isEmpty()) result = "[join]";
        ctx.putOutput(nodeId, result);
        runWriter.updateAndPersist(ctx.run(), nodeId, "completed", "", result);
        log.infof("══ WORKFLOW ⑃ [%s] JOIN %s — aggregated %d/%d results (missing: %s) ══",
            ctx.runId(), nodeId,
            expected.size() - missing.size(), expected.size(), missing.isEmpty() ? "none" : missing);
        return true;
    }
}