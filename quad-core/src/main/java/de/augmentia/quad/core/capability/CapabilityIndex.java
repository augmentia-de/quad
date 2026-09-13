package de.augmentia.quad.core.capability;

import java.util.List;

public interface CapabilityIndex {
    void index(Capability capability);
    void remove(String name);
    List<Capability> search(String query, int topK);
    List<Capability> search(String query, int topK, String tenantId);
    void clear();
}