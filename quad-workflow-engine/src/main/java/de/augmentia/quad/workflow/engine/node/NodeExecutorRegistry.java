package de.augmentia.quad.workflow.engine.node;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy registry that maps node types to their {@link NodeExecutor} implementations.
 *
 * <p>Previously this stood here as {@code @Inject @Any Instance<NodeExecutor>}: the registry
 * received its candidates from Arc. That tied it to a
 * DI container, although the registry itself does not have to resolve
 * anything - it only maps a type name to an instance.
 *
 * <p>Now it receives the list in the constructor. That keeps the class
 * container-free (the Spring Boot worker can use it directly) and makes
 * the bindings explicit: {@link #NodeExecutorRegistry(List)} is now
 * the only place where it is known which executors exist at all.
 * A new node type therefore needs two changes — the new class and
 * its entry in this list — instead of silently joining
 * the container.
 */
public class NodeExecutorRegistry {

    private static final Logger log = Logger.getLogger(NodeExecutorRegistry.class);

    private final Map<String, NodeExecutor> byType = new HashMap<>();

    public NodeExecutorRegistry(List<NodeExecutor> executors) {
        for (NodeExecutor e : executors) {
            if (e == null) continue;
            for (String type : e.getTypes()) {
                byType.put(type, e);
            }
            log.infof("NodeExecutor bound: %s -> %s", e.getTypes(), e.getClass().getSimpleName());
        }
    }

    /**
     * Returns the executor for the given node type, or {@code null} if no
     * specialized executor exists (then the engine handles the standard agent path).
     */
    public NodeExecutor forType(String type) {
        if (type == null) return null;
        NodeExecutor e = byType.get(type);
        if (e != null) return e;
        // special cases that AgentNodeExecutor handles
        if ("agent".equals(type) || "messaging-in".equals(type) || "messaging-out".equals(type)) {
            return byType.get("agent");
        }
        return null;
    }

    /** All registered types; for diagnostics and startup logging. */
    public List<String> registeredTypes() {
        return new ArrayList<>(byType.keySet());
    }
}