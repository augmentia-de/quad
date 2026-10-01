package de.augmentia.quad.workflow.engine.node;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Strategy registry that maps node types to their {@link NodeExecutor} implementations.
 *
 * <p>Vorher stand hier {@code @Inject @Any Instance<NodeExecutor>}: die Registry
 * bekam ihre Kandidaten von Arc geliefert. Damit war sie an einen
 * DI-Container gebunden, obwohl die Registry selbst gar nichts aufloesen
 * muss - sie bildet nur einen Typnamen auf eine Instanz ab.
 *
 * <p>Jetzt bekommt sie die Liste im Konstruktor. Das haelt die Klasse
 * containerfrei (der Spring-Boot-Worker kann sie direkt benutzen) und macht
 * die Bindungen explizit: {@link #NodeExecutorRegistry(List)} ist jetzt die
 * einzige Stelle, an der bekannt ist, welche Executors es ueberhaupt gibt.
 * Ein neuer Knotentyp braucht damit zwei Aenderungen - die neue Klasse und
 * ihren Eintrag in dieser Liste -, statt sich still in den Container
 * einzureihen.
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
            log.infof("NodeExecutor gebunden: %s -> %s", e.getTypes(), e.getClass().getSimpleName());
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
        // Spezialfaelle, die AgentNodeExecutor behandelt
        if ("agent".equals(type) || "messaging-in".equals(type) || "messaging-out".equals(type)) {
            return byType.get("agent");
        }
        return null;
    }

    /** Alle registrierten Typen; fuer Diagnose und Startup-Logging. */
    public List<String> registeredTypes() {
        return new ArrayList<>(byType.keySet());
    }
}