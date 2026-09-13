package de.augmentia.quad.core;

public record ToolSetChangedEvent(java.util.Set<String> added, java.util.Set<String> removed) {}