package de.augmentia.quad.core.capability;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Qdrant-based vector index.
 * Accepts a qdrant-client-based implementation if available.
 * For simple cases, this can be used as a stub.
 */
public class QdrantCapabilityIndex implements CapabilityIndex {

    private final ObjectMapper objectMapper;
    private final List<Capability> inmemoryIndex = new ArrayList<>();

    public QdrantCapabilityIndex(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void index(Capability capability) {
        inmemoryIndex.add(capability);
    }

    @Override
    public void remove(String name) {
        inmemoryIndex.removeIf(c -> c.name().equals(name));
    }

    @Override
    public List<Capability> search(String query, int topK) {
        return search(query, topK, null);
    }

    @Override
    public List<Capability> search(String query, int topK, String tenantId) {
        return inmemoryIndex.stream()
            .filter(c -> c.allowedTenants().isEmpty() || c.allowedTenants().contains(tenantId))
            .limit(topK)
            .toList();
    }

    @Override
    public void clear() {
        inmemoryIndex.clear();
    }
}