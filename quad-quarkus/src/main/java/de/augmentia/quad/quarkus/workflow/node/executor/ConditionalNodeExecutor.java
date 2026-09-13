package de.augmentia.quad.quarkus.workflow.node.executor;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.quarkus.workflow.WorkflowCondition;
import de.augmentia.quad.quarkus.workflow.internal.GraphUtils;
import de.augmentia.quad.quarkus.workflow.context.InputBuilder;
import de.augmentia.quad.quarkus.workflow.internal.RunStateWriter;
import de.augmentia.quad.quarkus.workflow.internal.WorkflowExecutionContext;
import de.augmentia.quad.quarkus.workflow.node.NodeConfig;
import de.augmentia.quad.quarkus.workflow.node.NodeExecutor;
import de.augmentia.quad.quarkus.workflow.node.NodeRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conditional branching node. Evaluates {@code condition} against the current
 * node input and executes the true/false target segment depending on the result;
 * unreachable downstream nodes are marked as "skipped by conditional".
 */
@ApplicationScoped
public class ConditionalNodeExecutor implements NodeExecutor {

    private static final Logger log = Logger.getLogger(ConditionalNodeExecutor.class);

    @Inject
    InputBuilder inputBuilder;
    @Inject
    RunStateWriter runWriter;

    @Override
    public Set<String> getTypes() {
        return Set.of("conditional");
    }

    @Override
    public boolean execute(Map<String, Object> node, List<Map<String, Object>> allNodes,
                           List<Map<String, Object>> edges, WorkflowExecutionContext ctx, NodeRunner runner) {
        String nodeId = NodeConfig.id(node);
        AgentSessionState sessionState = ctx.sessionState();
        String conditionText = NodeConfig.str(node, "condition", "");
        String inputContext = inputBuilder.buildNodeInput(node, edges, ctx.outputs(), sessionState, ctx.initialData());
        boolean met = WorkflowCondition.matches(conditionText, inputContext, 0);
        String outcomeLine = met ? "condition met" : "condition not met";

        ctx.putOutput(nodeId, outcomeLine);
        runWriter.updateAndPersist(ctx.run(), nodeId, "completed", inputContext, outcomeLine);

        Set<String> downstreamReachable = GraphUtils.transitivelyReachable(nodeId, edges);
        String trueTargetId = NodeConfig.str(node, "trueTargetId", "");
        String falseTargetId = NodeConfig.str(node, "falseTargetId", "");

        log.infof("══ WORKFLOW ⑂ [%s] CONDITIONAL %s — met=%s → true[%s] / false[%s] ══",
            ctx.runId(), nodeId, met, trueTargetId, falseTargetId);

        if (!met) {
            if (falseTargetId != null && !falseTargetId.isBlank()) {
                executeDownstreamSegment(falseTargetId, allNodes, edges, ctx, runner);
                for (String d : downstreamReachable) {
                    if (ctx.hasOutput(d)) continue;
                    ctx.putOutput(d, "[skipped by conditional " + nodeId + "]");
                    runWriter.updateAndPersist(ctx.run(), d, "completed", "", "[skipped by conditional " + nodeId + "]");
                }
            } else {
                for (String d : downstreamReachable) {
                    if (ctx.hasOutput(d)) continue;
                    ctx.putOutput(d, "[skipped by conditional " + nodeId + "]");
                    runWriter.updateAndPersist(ctx.run(), d, "completed", "", "[skipped by conditional " + nodeId + "]");
                }
            }
        } else {
            if (trueTargetId != null && !trueTargetId.isBlank()) {
                executeDownstreamSegment(trueTargetId, allNodes, edges, ctx, runner);
                for (String d : downstreamReachable) {
                    if (ctx.hasOutput(d)) continue;
                    ctx.putOutput(d, "[skipped by conditional " + nodeId + "]");
                    runWriter.updateAndPersist(ctx.run(), d, "completed", "", "[skipped by conditional " + nodeId + "]");
                }
            }
        }
        log.infof("Conditional %s condition=%s met=%s trueTarget=%s falseTarget=%s",
            nodeId, conditionText, met, trueTargetId, falseTargetId);
        return true;
    }

    private void executeDownstreamSegment(String startNodeId, List<Map<String, Object>> allNodes,
                                          List<Map<String, Object>> edges, WorkflowExecutionContext ctx,
                                          NodeRunner runner) {
        Set<String> reachableFromStart = new LinkedHashSet<>();
        GraphUtils.computeReachableFrom(startNodeId, edges, reachableFromStart);

        List<Map<String, Object>> topo = GraphUtils.topologicalSort(allNodes, edges);
        for (Map<String, Object> n : topo) {
            String nid = NodeConfig.id(n);
            if (reachableFromStart.contains(nid) && !ctx.hasOutput(nid)) {
                runner.runNode(n, allNodes, edges, ctx, Set.of());
            }
        }
    }
}