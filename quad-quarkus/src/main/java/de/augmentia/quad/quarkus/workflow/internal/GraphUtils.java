package de.augmentia.quad.quarkus.workflow.internal;

import de.augmentia.quad.quarkus.workflow.node.NodeConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Pure, stateless helpers for the working graph topology and navigation.
 * Contains no runtime or side effects (no LLM, messaging or persistence logic).
 */
public final class GraphUtils {

    private GraphUtils() {
    }

    public static List<String> orderedSuccessors(String nodeId, List<Map<String, Object>> allNodes,
                                                 List<Map<String, Object>> edges) {
        List<String> direct = new ArrayList<>();
        for (var edge : edges) {
            if (nodeId.equals(String.valueOf(edge.get("source")))) {
                String target = String.valueOf(edge.get("target"));
                if (!direct.contains(target)) direct.add(target);
            }
        }
        if (direct.isEmpty()) return direct;
        List<Map<String, Object>> ordered = topologicalSort(allNodes, edges);
        Map<String, Integer> topoIndex = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            topoIndex.put(String.valueOf(ordered.get(i).get("id")), i);
        }
        direct.sort(Comparator.comparingInt(t -> topoIndex.getOrDefault(t, Integer.MAX_VALUE)));
        return direct;
    }

    public static Set<String> transitivelyReachable(String nodeId, List<Map<String, Object>> edges) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(nodeId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (var edge : edges) {
                if (current.equals(String.valueOf(edge.get("source")))) {
                    String target = String.valueOf(edge.get("target"));
                    if (seen.add(target)) queue.add(target);
                }
            }
        }
        seen.remove(nodeId);
        return seen;
    }

    public static void computeReachableFrom(String sourceId, List<Map<String, Object>> edges, Set<String> result) {
        Deque<String> queue = new ArrayDeque<>();
        queue.add(sourceId);
        result.add(sourceId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (var edge : edges) {
                if (cur.equals(edge.get("source"))) {
                    String tgt = String.valueOf(edge.get("target"));
                    if (result.add(tgt)) {
                        queue.add(tgt);
                    }
                }
            }
        }
    }

    public static String combineOutputs(List<String> keys, Map<String, String> outputs) {
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            String value = outputs.get(key);
            if (value != null) {
                sb.append("[").append(key).append("]: ").append(value).append("\n\n");
            }
        }
        return sb.toString();
    }

    public static Map<String, Object> nodeById(List<Map<String, Object>> allNodes, String id) {
        if (id == null) return null;
        for (var n : allNodes) {
            if (id.equals(String.valueOf(n.get("id")))) return n;
        }
        return null;
    }

    /** IDs of nodes that feed directly into the given node (incoming edges). */
    public static List<String> predecessorIds(String nodeId, List<Map<String, Object>> edges) {
        List<String> preds = new ArrayList<>();
        for (var edge : edges) {
            if (nodeId.equals(String.valueOf(edge.get("target")))) {
                preds.add(String.valueOf(edge.get("source")));
            }
        }
        return preds;
    }

    /** The first downstream successor of the given node, or null. */
    public static String firstSuccessor(String nodeId, List<Map<String, Object>> edges) {
        for (var edge : edges) {
            if (nodeId.equals(String.valueOf(edge.get("source")))) {
                return String.valueOf(edge.get("target"));
            }
        }
        return null;
    }

    /**
     * Determines the join policy for a target node from its incoming edges.
     * Only explicit 'any'/'one' edges change the default 'all'.
     */
    public static String joinPolicyOf(String nodeId, List<Map<String, Object>> edges) {
        for (var edge : edges) {
            if (nodeId.equals(String.valueOf(edge.get("target")))) {
                Object p = edge.get("joinPolicy");
                if (p != null && ("any".equals(String.valueOf(p)) || "one".equals(String.valueOf(p)))) {
                    return "any";
                }
            }
        }
        return "all";
    }

    /**
     * Builds the loop body region.
     * <p>
     * Default (no anchor): the loop body is the ENTIRE downstream region — every node
     * transitively reachable from the loop node (multiple agents), i.e. the whole segment
     * up to where the flow would return to the loop node. This lets any number of agents
     * be enclosed in a single loop.
     * <p>
     * If config.loopTargetId is set, the body is narrowed to nodes reachable from that
     * anchor within the loop's downstream (backwards-compatible explicit segment start).
     */
    public static List<String> computeLoopBody(String nodeId, Map<String, Object> loopNode,
                                               List<Map<String, Object>> allNodes, List<Map<String, Object>> edges) {
        String loopTargetId = NodeConfig.str(loopNode, "loopTargetId", "");

        List<Map<String, Object>> topo = topologicalSort(allNodes, edges);
        Set<String> downstreamOfLoop = transitivelyReachable(nodeId, edges);

        if (loopTargetId != null && !loopTargetId.isBlank()) {
            if (!downstreamOfLoop.contains(loopTargetId)) {
                return orderedSuccessors(nodeId, allNodes, edges);
            }
            Set<String> backEdgeSources = new HashSet<>();
            for (var e : edges) {
                if (nodeId.equals(String.valueOf(e.get("target")))) {
                    backEdgeSources.add(String.valueOf(e.get("source")));
                }
            }
            List<String> result = new ArrayList<>();
            Deque<String> queue = new ArrayDeque<>();
            Set<String> visited = new LinkedHashSet<>();
            visited.add(loopTargetId);
            queue.add(loopTargetId);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                if (!downstreamOfLoop.contains(cur)) continue;
                result.add(cur);
                if (backEdgeSources.contains(cur)) continue;
                for (var e : edges) {
                    if (cur.equals(String.valueOf(e.get("source")))) {
                        String tgt = String.valueOf(e.get("target"));
                        if (visited.add(tgt)) queue.add(tgt);
                    }
                }
            }
            Set<String> bodySet = new LinkedHashSet<>(result);
            List<String> ordered = new ArrayList<>();
            for (Map<String, Object> n : topo) {
                if (bodySet.contains(String.valueOf(n.get("id")))) ordered.add(String.valueOf(n.get("id")));
            }
            return ordered;
        } else {
            List<String> result = new ArrayList<>();
            for (Map<String, Object> n : topo) {
                String nid = String.valueOf(n.get("id"));
                if (downstreamOfLoop.contains(nid)) {
                    result.add(nid);
                }
            }
            return result;
        }
    }

    public static String lastOrEmpty(Map<String, String> map) {
        String last = "";
        for (var value : map.values()) {
            last = value;
        }
        return last;
    }

    public static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl > 0 ? s.substring(0, nl) : (s.length() > 120 ? s.substring(0, 120) + "..." : s);
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> topologicalSort(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, Map<String, Object>> nodeById = new LinkedHashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();

        for (var n : nodes) {
            String id = String.valueOf(n.get("id"));
            inDegree.put(id, 0);
            nodeById.put(id, n);
            adj.put(id, new ArrayList<>());
        }

        for (var e : edges) {
            String src = String.valueOf(e.get("source"));
            String tgt = String.valueOf(e.get("target"));
            adj.computeIfAbsent(src, k -> new ArrayList<>()).add(tgt);
            inDegree.merge(tgt, 1, Integer::sum);
        }

        var queue = new ArrayDeque<String>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        var result = new ArrayList<Map<String, Object>>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            result.add(nodeById.get(id));
            for (String next : adj.getOrDefault(id, List.of())) {
                int newDeg = inDegree.merge(next, -1, Integer::sum);
                if (newDeg == 0) queue.add(next);
            }
        }

        Set<String> completedIds = new LinkedHashSet<>();
        for (var n : result) completedIds.add(String.valueOf(n.get("id")));
        for (var entry : inDegree.entrySet()) {
            if (!completedIds.contains(entry.getKey())) {
                Map<String, Object> leftover = nodeById.get(entry.getKey());
                if (leftover != null && !result.contains(leftover)) {
                    result.add(leftover);
                }
            }
        }

        return result;
    }
}