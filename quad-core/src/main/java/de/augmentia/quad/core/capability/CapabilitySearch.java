package de.augmentia.quad.core.capability;

import java.util.List;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

public class CapabilitySearch {
    private final CapabilityIndex index;
    private final Cache<String, List<Capability>> cache;

    public CapabilitySearch(CapabilityIndex index, int cacheSize, int expireAfterMinutes) {
        this.index = index;
        this.cache = Caffeine.newBuilder()
            .maximumSize(cacheSize)
            .expireAfterWrite(java.time.Duration.ofMinutes(expireAfterMinutes))
            .build();
    }

    public List<Capability> search(String query, int topK) {
        String cacheKey = "global:" + query + ":" + topK;
        return cache.get(cacheKey, k -> index.search(query, topK));
    }

    public List<Capability> search(String query, int topK, String tenantId) {
        String cacheKey = tenantId + ":" + query + ":" + topK;
        return cache.get(cacheKey, k -> index.search(query, topK, tenantId));
    }

    public void invalidate(String query) {
        cache.invalidateAll();
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public void index(Capability capability) {
        index.index(capability);
        cache.invalidateAll();
    }

    public void remove(String name) {
        index.remove(name);
        cache.invalidateAll();
    }
}