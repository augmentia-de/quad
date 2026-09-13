package de.augmentia.quad.quarkus.workflow.node;

import java.util.Map;

/**
 * Pure helper for safe reading of node and config fields from the raw
 * map representation of workflow nodes.
 */
public final class NodeConfig {

    private NodeConfig() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> of(Map<String, Object> node) {
        Object raw = node.get("config");
        if (raw instanceof Map) {
            return (Map<String, Object>) raw;
        }
        return Map.of();
    }

    public static String str(Map<String, Object> node, String key, String fallback) {
        Object v = of(node).get(key);
        return v != null ? String.valueOf(v) : fallback;
    }

    public static int intOf(Map<String, Object> node, String key, int fallback) {
        Object v = of(node).get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** JSON-Arbeitsmodus: true at jsonOutput (neu) oder jsonInput (Legacy from bestehen UIs). */
    public static boolean isJsonOutputMode(Map<String, Object> config) {
        if (config.get("jsonOutput") instanceof Boolean jo && jo) return true;
        return config.get("jsonInput") instanceof Boolean ji && ji;
    }

    public static String type(Map<String, Object> node) {
        return node.get("type") != null ? String.valueOf(node.get("type")) : "";
    }

    public static String id(Map<String, Object> node) {
        return String.valueOf(node.get("id"));
    }

    /** Expected result ids for a join; empty = all incoming predecessors. */
    public static java.util.List<String> joinResultIds(Map<String, Object> node) {
        Map<String, Object> config = of(node);
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (config.get("resultIds") instanceof java.util.Collection<?> c) {
            for (Object o : c) ids.add(String.valueOf(o));
        }
        return ids;
    }
}