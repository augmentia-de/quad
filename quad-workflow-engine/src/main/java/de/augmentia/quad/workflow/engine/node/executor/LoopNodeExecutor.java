package de.augmentia.quad.workflow.engine.node.executor;

import de.augmentia.quad.workflow.engine.WorkflowCondition;
import de.augmentia.quad.workflow.engine.internal.GraphUtils;
import de.augmentia.quad.workflow.engine.internal.RunStateWriter;
import de.augmentia.quad.workflow.engine.internal.WorkflowExecutionContext;
import de.augmentia.quad.workflow.engine.node.NodeConfig;
import de.augmentia.quad.workflow.engine.node.NodeExecutor;
import de.augmentia.quad.workflow.engine.node.NodeRunner;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Iterative loop node. The loop body is computed from the graph structure
 * ({@code computeLoopBody}) and re-executed on each iteration; then the
 * exit condition ({@code exitCondition}) is checked against the combined body outputs.
 *
 * <p>Two refinements over the original behaviour:
 * <ul>
 *   <li>The body outputs are still cleared before each iteration so a resume does not re-run a
 *       completed branch, but every produced value is kept in the run's output history first. A
 *       node in iteration <i>N</i> can therefore read what a sibling produced in <i>N-1</i>.</li>
 *   <li>{@code exitConditionSource} narrows the exit check to a single node. Without it the check
 *       runs against the concatenation of <em>all</em> body outputs, so a substring occurring in
 *       the designer's JSON could end the loop while the reviewer rejected it.</li>
 * </ul>
 */
public class LoopNodeExecutor implements NodeExecutor {
    public LoopNodeExecutor(final RunStateWriter runWriter) {
        this.runWriter = runWriter;
    }

    private static final Logger log = Logger.getLogger(LoopNodeExecutor.class);

    private final RunStateWriter runWriter;

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
        String exitConditionSource = NodeConfig.str(node, "exitConditionSource", "");
        String aggregate = NodeConfig.str(node, "aggregate", "last");
        boolean retainOutput = Boolean.TRUE.equals(NodeConfig.of(node).get("retainOutput"));

        List<String> bodyIds = GraphUtils.computeLoopBody(nodeId, node, allNodes, edges);
        List<String> bodyOutputKeys = new ArrayList<>(bodyIds);

        runWriter.updateAndPersist(ctx.run(), nodeId, "running", "", "");

        log.infof("══ WORKFLOW ⟳ [%s] LOOP %s starting — maxIterations=%d, exitCondition=\"%s\", exitSource=\"%s\", body=%s ══",
            ctx.runId(), nodeId, maxIterations, exitCondition, exitConditionSource, bodyIds);

        int startIter = ctx.loopIterations().getOrDefault(nodeId, 1);
        if (startIter > maxIterations) startIter = maxIterations;

        String iterationData = ctx.initialData();
        int iterations = 0;
        boolean exitReached = false;
        // Every iteration's combined output, for aggregate=all.
        List<String> allIterationData = new ArrayList<>();

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
            // clearOutput keeps the history; remove() would discard the previous round.
            for (String bodyId : bodyIds) {
                ctx.clearOutput(bodyId);
                // Tag each body node with the current round so putOutput records it and
                // `resolveHistory(source, "iteration:N")` can select the right value.
                ctx.loopIterations().put(bodyId, iter);
            }
            for (String bodyId : bodyIds) {
                Map<String, Object> body = GraphUtils.nodeById(allNodes, bodyId);
                if (body == null) continue;
                runner.runNode(body, allNodes, edges, ctx, Set.of());
            }

            // Values produced this iteration are already in the history via putOutput. Capture
            // them now, before the next iteration clears `outputs`.
            String combined = GraphUtils.combineOutputs(bodyOutputKeys, ctx.outputs());
            iterationData = combined;
            allIterationData.add(combined);

            String probe = exitConditionSource.isBlank()
                    ? combined
                    : String.join("\n", ctx.resolveHistory(exitConditionSource, "iteration:" + iter));
            if (WorkflowCondition.matches(exitCondition, probe, iter)) {
                exitReached = true;
                break;
            }
        }

        String summary = "Loop iterations=" + iterations + " "
            + (exitReached ? "exitReached=true" : "maxIterationsReached=true")
            + "\n" + aggregateLabel(aggregate) + ":\n" + aggregateOutput(aggregate, iterationData, allIterationData);
        ctx.putOutput(nodeId, summary);
        if (retainOutput) {
            // Keep the body outputs of the final iteration readable by later nodes.
            log.infof("Loop %s retains body outputs of iteration %d", nodeId, iterations);
        }
        runWriter.updateAndPersist(ctx.run(), nodeId, "completed", "", summary);
        log.infof("Loop %s finished after %d iterations (exitReached=%s, bodySize=%d)",
            nodeId, iterations, exitReached, bodyIds.size());
        return true;
    }

    private static String aggregateLabel(String aggregate) {
        return switch (aggregate == null ? "last" : aggregate) {
            case "all" -> "All iteration outputs";
            case "first" -> "First iteration output";
            default -> "Last iteration output";
        };
    }

    private static String aggregateOutput(String aggregate, String last, List<String> all) {
        String mode = aggregate == null ? "last" : aggregate;
        if ("all".equals(mode)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < all.size(); i++) {
                sb.append("--- iteration ").append(i + 1).append(" ---\n").append(all.get(i)).append("\n");
            }
            return sb.toString();
        }
        if ("first".equals(mode)) {
            return all.isEmpty() ? last : all.get(0);
        }
        return last;
    }
}