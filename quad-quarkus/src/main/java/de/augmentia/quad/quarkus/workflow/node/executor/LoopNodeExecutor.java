package de.augmentia.quad.quarkus.workflow.node.executor;

import de.augmentia.quad.quarkus.workflow.WorkflowCondition;
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
 * Iterative loop node. The loop body is computed from the graph structure
 * ({@code computeLoopBody}) and re-executed on each iteration; then the
 * exit condition ({@code exitCondition}) is checked against the combined body outputs.
 */
@ApplicationScoped
public class LoopNodeExecutor implements NodeExecutor {

    private static final Logger log = Logger.getLogger(LoopNodeExecutor.class);

    @Inject
    RunStateWriter runWriter;

    @Override
    public Set<String> getTypes() {
        return Set.of("loop");
    }

    @Override
    public boolean execute(Map<String, Object> node, List<Map<String, Object>> allNodes,
                           List<Map<String, Object>> edges, WorkflowExecutionContext ctx, NodeRunner runner) {
        String nodeId = NodeConfig.id(node);
        int maxIterations = NodeConfig.intOf(node, "maxIterations", 5);
        if (maxIterations < 1) maxIterations = 1;
        String exitCondition = NodeConfig.str(node, "exitCondition", "");

        List<String> bodyIds = GraphUtils.computeLoopBody(nodeId, node, allNodes, edges);
        List<String> bodyOutputKeys = new ArrayList<>(bodyIds);

        runWriter.updateAndPersist(ctx.run(), nodeId, "running", "", "");

        log.infof("══ WORKFLOW ⟳ [%s] LOOP %s starting — maxIterations=%d, exitCondition=\"%s\", body=%s ══",
            ctx.runId(), nodeId, maxIterations, exitCondition, bodyIds);

        int startIter = ctx.loopIterations().getOrDefault(nodeId, 1);
        if (startIter > maxIterations) startIter = maxIterations;

        String iterationData = ctx.initialData();
        int iterations = 0;
        boolean exitReached = false;
        for (int iter = startIter; iter <= maxIterations; iter++) {
            iterations = iter;
            ctx.loopIterations().put(nodeId, iter);
            // Checkpoint BEFORE body execution: on Kill/Continue the snapshot carries the
            // currently running (not yet completed) iteration; Resume resumes exactly there.
            runWriter.saveCheckpoint(ctx.run(), ctx);
            log.infof("══ WORKFLOW ⟳ [%s] LOOP %s — iteration %d/%d (resumeAt=%d) ══",
                ctx.runId(), nodeId, iter, maxIterations, startIter);
            // Each iteration clears the body outputs and recomputes the body. On Resume only
            // the iteration counter is advanced to startIter -> already completed
            // iterations 1..startIter-1 are NOT re-run (no double-run of completed branches).
            for (String bodyId : bodyIds) {
                ctx.outputs().remove(bodyId);
            }
            for (String bodyId : bodyIds) {
                Map<String, Object> body = GraphUtils.nodeById(allNodes, bodyId);
                if (body == null) continue;
                runner.runNode(body, allNodes, edges, ctx, Set.of());
            }
            String combined = GraphUtils.combineOutputs(bodyOutputKeys, ctx.outputs());
            iterationData = combined;
            if (WorkflowCondition.matches(exitCondition, combined, iter)) {
                exitReached = true;
                break;
            }
        }

        String summary = "Loop iterations=" + iterations + " "
            + (exitReached ? "exitReached=true" : "maxIterationsReached=true")
            + "\nLast iteration output:\n" + iterationData;
        ctx.putOutput(nodeId, summary);
        runWriter.updateAndPersist(ctx.run(), nodeId, "completed", "", summary);
        log.infof("Loop %s finished after %d iterations (exitReached=%s, bodySize=%d)",
            nodeId, iterations, exitReached, bodyIds.size());
        return true;
    }
}