package de.augmentia.quad.core.capability;

public record Capability(
    String name,
    String description,
    String methodRef,
    String source,
    String type,
    java.util.Set<String> allowedTenants,
    double score
) {
    public Capability(String name, String description, String methodRef, String source, String type) {
        this(name, description, methodRef, source, type, java.util.Set.of(), 0.0);
    }
}