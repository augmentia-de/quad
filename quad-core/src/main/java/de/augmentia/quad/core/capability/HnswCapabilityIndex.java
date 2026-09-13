package de.augmentia.quad.core.capability;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HnswCapabilityIndex implements CapabilityIndex {
    private final Map<String, Capability> capabilities = new ConcurrentHashMap<>();

    @Override
    public void index(Capability capability) {
        capabilities.put(capability.name(), capability);
    }

    @Override
    public void remove(String name) {
        capabilities.remove(name);
    }

    @Override
    public List<Capability> search(String query, int topK) {
        String q = query.toLowerCase();
        return capabilities.values().stream()
            .filter(c -> c.name().toLowerCase().contains(q)
                || c.description().toLowerCase().contains(q))
            .sorted((a, b) -> {
                int aScore = score(a, q);
                int bScore = score(b, q);
                return Integer.compare(bScore, aScore);
            })
            .limit(topK)
            .toList();
    }

    @Override
    public List<Capability> search(String query, int topK, String tenantId) {
        String q = query.toLowerCase();
        return capabilities.values().stream()
            .filter(c -> c.allowedTenants().isEmpty() || c.allowedTenants().contains(tenantId))
            .filter(c -> c.name().toLowerCase().contains(q)
                || c.description().toLowerCase().contains(q))
            .sorted((a, b) -> {
                int aScore = score(a, q);
                int bScore = score(b, q);
                return Integer.compare(bScore, aScore);
            })
            .limit(topK)
            .toList();
    }

    @Override
    public void clear() {
        capabilities.clear();
    }

    private int score(Capability c, String query) {
        int s = 0;
        if (c.name().toLowerCase().contains(query)) s += 10;
        if (c.description().toLowerCase().contains(query)) s += 5;
        return s;
    }
}