package de.augmentia.quad.quarkus.workflow.node.executor;

import de.augmentia.quad.quarkus.workflow.internal.GraphUtils;
import de.augmentia.quad.quarkus.workflow.internal.RunStateWriter;
import de.augmentia.quad.quarkus.workflow.internal.WorkflowExecutionContext;
import de.augmentia.quad.quarkus.workflow.internal.WorkflowSupport;
import de.augmentia.quad.quarkus.workflow.node.NodeConfig;
import de.augmentia.quad.quarkus.workflow.node.NodeExecutor;
import de.augmentia.quad.quarkus.workflow.node.NodeRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asynchronous node. Executes the referenced child node non-blockingly on a virtual
 * thread and waits up to {@code timeout} ms for its completion (local or via an external
 * deferred-completion callback). On timeout a marker is set as the result.
 */
@ApplicationScoped
public class AsyncNodeExecutor implements NodeExecutor {

    private static final Logger log = Logger.getLogger(AsyncNodeExecutor.class);

    @Inject
    WorkflowSupport support;
    @Inject AgentNodeExecutor agentExecutor;
    @Inject
    RunStateWriter runWriter;

    @Override
    public Set<String> getTypes() {
        return Set.of("async");
    }

    @Override
    public boolean execute(Map<String, Object> node, List<Map<String, Object>> allNodes,
                           List<Map<String, Object>> edges, WorkflowExecutionContext ctx, NodeRunner runner) {
        String nodeId = NodeConfig.id(node);
        if (ctx.run() != null) runWriter.updateNode(ctx.run(), nodeId, "running", "", "async started");

        long timeout = support.nodeTimeoutMs(node, support.defaultTimeoutMs());

        String childId = NodeConfig.str(node, "refNodeId", "");
        if (childId.isBlank()) childId = GraphUtils.firstSuccessor(nodeId, edges);
        final String childIdFinal = childId;
        Map<String, Object> child = childIdFinal != null ? GraphUtils.nodeById(allNodes, childIdFinal) : null;
        if (child == null) {
            ctx.putOutput(nodeId, "[async: no child node]");
            if (ctx.run() != null) runWriter.updateAndPersist(ctx.run(), nodeId, "completed", "", "[async: no child node]");
            return true;
        }

        support.executor().submit(() -> {
            try {
                WorkflowExecutionContext childCtx = new WorkflowExecutionContext(
                    ctx.runId(), ctx.run(), ctx.initialData(), ctx.initialData(), support.newRunState(), false);
                String out = agentExecutor.runChildNode(child, allNodes, edges, childCtx.outputs(), childCtx, childIdFinal);
                if (out == null) {
                    runner.runNode(child, allNodes, edges, childCtx, Set.of());
                    out = childCtx.output(childIdFinal);
                }
                synchronized (ctx.outputs()) {
                    ctx.putOutput(nodeId, out);
                }
                support.completeDeferred(ctx.runId(), nodeId, out);
            } catch (Exception e) {
                if (ctx.run() != null) {
                    runWriter.updateAndPersist(ctx.run(), nodeId, "failed", "", e.getMessage());
                }
            }
        });

        boolean resolved = false;
        if (timeout > 0) {
            long deadline = System.currentTimeMillis() + timeout;
            while (System.currentTimeMillis() < deadline) {
                if (ctx.hasOutput(nodeId)) { resolved = true; break; }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    resolved = true;
                    break;
                }
            }
        } else {
            while (!ctx.hasOutput(nodeId)) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            resolved = ctx.hasOutput(nodeId);
        }
        if (!resolved) {
            String marker = timeout > 0
                ? "[async: deferred completion timed out after " + timeout + "ms]"
                : "[async: interrupted]";
            synchronized (ctx.outputs()) { ctx.putOutput(nodeId, marker); }
            if (ctx.run() != null) runWriter.updateNode(ctx.run(), nodeId, "completed", "", marker);
        } else if (ctx.run() != null) {
            runWriter.updateNode(ctx.run(), nodeId, "completed", "", ctx.output(nodeId));
        }
        if (ctx.run() != null) runWriter.persist(ctx.run());
        synchronized (support.monitor) { support.monitor.notifyAll(); }
        return true;
    }
}