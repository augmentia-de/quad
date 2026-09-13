package de.augmentia.quad.quarkus.workflow.engine;

import de.augmentia.quad.quarkus.workflow.internal.*;
import de.augmentia.quad.quarkus.workflow.node.NodeConfig;
import de.augmentia.quad.quarkus.workflow.node.NodeRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-aware ready-batch scheduler for parallel workflow sections. It decides
 * solely which nodes are fireable when (predecessor outputs, join/loop special cases,
 * timeout deadlines) and delegates execution to a {@link NodeRunner}. It knows
 * nothing about LLMs, prompts or messaging channels.
 */
@ApplicationScoped
public class WorkflowScheduler {

    private static final Logger log = Logger.getLogger(WorkflowScheduler.class);

    @Inject
    WorkflowSupport support;
    @Inject
    RunStateWriter runWriter;

    /**
     * Execution control for parallel sections to execute (fork/async/join).
     * Runs all fireable nodes on virtual threads until all are resolved.
     */
    public void executeParallel(List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
                                WorkflowExecutionContext ctx, NodeRunner runner) {
        Set<String> started = new java.util.HashSet<>();
        List<Map<String, Object>> ordered = GraphUtils.topologicalSort(nodes, edges);

        while (true) {
            boolean fired = false;
            for (var node : ordered) {
                String id = NodeConfig.id(node);
                if (started.contains(id)) continue;
                if (anyDepFailed(id, edges, ctx.statuses())) {
                    started.add(id);
                    ctx.statuses().put(id, "skipped");
                    ctx.putOutput(id, "[skipped: dependency failed]");
                    continue;
                }
                if (isRunnable(node, id, edges, ctx)) {
                    started.add(id);
                    fired = true;
                    support.executor().submit(() ->
                        runNodeParallel(node, nodes, edges, ctx, runner));
                }
            }

            if (allResolved(ordered, ctx)) break;
            if (fired) {
                try { Thread.sleep(25); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                continue;
            }
            long waitMs = nextDeadlineWaitMs(ctx.joinDeadline());
            synchronized (support.monitor) {
                try { support.monitor.wait(Math.min(Math.max(waitMs, 1), 250)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void runNodeParallel(Map<String, Object> node, List<Map<String, Object>> allNodes,
                                 List<Map<String, Object>> edges, WorkflowExecutionContext ctx, NodeRunner runner) {
        String id = NodeConfig.id(node);
        try {
            runner.runNode(node, allNodes, edges, ctx, Set.of());
            if (!ctx.hasOutput(id)) ctx.putOutput(id, "[done]");
        } catch (Exception e) {
            ctx.statuses().put(id, "failed");
            if (!ctx.hasOutput(id)) ctx.putOutput(id, "[failed: " + e.getMessage() + "]");
            if (ctx.run() != null) {
                runWriter.updateAndPersist(ctx.run(), id, "failed", "", e.getMessage());
            }
        } finally {
            synchronized (support.monitor) { support.monitor.notifyAll(); }
        }
    }

    private boolean allResolved(List<Map<String, Object>> ordered, WorkflowExecutionContext ctx) {
        for (var node : ordered) {
            String id = NodeConfig.id(node);
            if (!ctx.hasOutput(id)
                && !"failed".equals(ctx.statuses().get(id))
                && !"skipped".equals(ctx.statuses().get(id))) {
                return false;
            }
        }
        return true;
    }

    /** Whether a node may fire now: deps resolved, or join/async fired by timeout. */
    private boolean isRunnable(Map<String, Object> node, String id, List<Map<String, Object>> edges,
                               WorkflowExecutionContext ctx) {
        if (ctx.hasOutput(id)) return false;
        String type = NodeConfig.type(node);
        if ("join".equals(type)) {
            List<String> expected = NodeConfig.joinResultIds(node);
            if (expected.isEmpty()) {
                if (allDepsResolved(id, edges, ctx.outputs(), GraphUtils.joinPolicyOf(id, edges))) return true;
                return activateOrExpired(id, edges, ctx, joinTimeoutMs(node));
            }
            boolean all = expected.stream().allMatch(ctx::hasOutput);
            if (all) return true;
            return activateOrExpired(id, edges, ctx, joinTimeoutMs(node));
        }
        if ("loop".equals(type)) {
            Set<String> selfRegion = GraphUtils.transitivelyReachable(id, edges);
            boolean allExternalMet = true;
            for (String p : GraphUtils.predecessorIds(id, edges)) {
                if (selfRegion.contains(p)) continue;
                if (!ctx.hasOutput(p)) {
                    allExternalMet = false;
                    break;
                }
            }
            return allExternalMet;
        }
        return allDepsResolved(id, edges, ctx.outputs(), GraphUtils.joinPolicyOf(id, edges));
    }

    /** Activates a timeout-tracking deadline on first dependency result; fires once expired. */
    private boolean activateOrExpired(String nodeId, List<Map<String, Object>> edges,
                                      WorkflowExecutionContext ctx, long timeout) {
        if (timeout <= 0) return false;
        List<String> preds = GraphUtils.predecessorIds(nodeId, edges);
        boolean active = preds.isEmpty() || preds.stream().anyMatch(ctx::hasOutput);
        if (active && !ctx.joinDeadline().containsKey(nodeId)) {
            ctx.joinDeadline().put(nodeId, System.currentTimeMillis() + timeout);
        }
        Long dl = ctx.joinDeadline().get(nodeId);
        return dl != null && System.currentTimeMillis() >= dl;
    }

    private long nextDeadlineWaitMs(Map<String, Long> joinDeadline) {
        long now = System.currentTimeMillis();
        long nearest = Long.MAX_VALUE;
        for (long dl : joinDeadline.values()) nearest = Math.min(nearest, dl);
        if (nearest == Long.MAX_VALUE) return 100;
        long diff = nearest - now;
        return diff <= 0 ? 1 : diff;
    }

    private long joinTimeoutMs(Map<String, Object> node) {
        return support.nodeTimeoutMs(node, 0);
    }

    private boolean allDepsResolved(String nodeId, List<Map<String, Object>> edges,
                                    Map<String, String> outputs, String joinPolicy) {
        List<String> preds = GraphUtils.predecessorIds(nodeId, edges);
        if (preds.isEmpty()) return true;
        if ("any".equals(joinPolicy)) {
            return preds.stream().anyMatch(outputs::containsKey);
        }
        return preds.stream().allMatch(outputs::containsKey);
    }

    private boolean anyDepFailed(String nodeId, List<Map<String, Object>> edges,
                                 Map<String, String> statuses) {
        for (String pid : GraphUtils.predecessorIds(nodeId, edges)) {
            if ("failed".equals(statuses.get(pid))) return true;
        }
        return false;
    }
}