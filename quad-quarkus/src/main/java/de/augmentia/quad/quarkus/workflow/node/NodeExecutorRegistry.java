package de.augmentia.quad.quarkus.workflow.node;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy registry that maps node types to their {@link NodeExecutor} implementations.
 * New node types register themselves automatically by implementing {@link NodeExecutor};
 * unknown types fall back to the agent/messaging standard path.
 */
@ApplicationScoped
public class NodeExecutorRegistry {

    private static final Logger log = Logger.getLogger(NodeExecutorRegistry.class);

    @Inject
    @Any
    Instance<NodeExecutor> executors;

    private final Map<String, NodeExecutor> byType = new HashMap<>();

    public void init() {
        if (!byType.isEmpty()) return;
        for (NodeExecutor e : iterateAll()) {
            for (String type : e.getTypes()) {
                byType.put(type, e);
            }
            log.infof("NodeExecutor bound: %s → %s", e.getTypes(), e.getClass().getSimpleName());
        }
    }

    /**
     * Returns the executor for the given node type, or {@code null} if no
     * specialized executor exists (then the engine handles the standard agent path).
     */
    public NodeExecutor forType(String type) {
        init();
        if (type == null) return null;
        NodeExecutor e = byType.get(type);
        if (e != null) return e;
        // specias that are handled by AgentNodeExecutor
        if ("agent".equals(type) || "messaging-in".equals(type) || "messaging-out".equals(type)) {
            return byType.get("agent");
        }
        return null;
    }

    private List<NodeExecutor> iterateAll() {
        var list = new java.util.ArrayList<NodeExecutor>();
        for (var h : executors.handles()) {
            try {
                list.add(h.get());
            } catch (Exception ex) {
                log.warnf("NodeExecutor instance unavailable: %s", ex.getMessage());
            }
        }
        return list;
    }
}