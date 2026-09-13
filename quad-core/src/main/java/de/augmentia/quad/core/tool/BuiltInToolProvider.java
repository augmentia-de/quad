package de.augmentia.quad.core.tool;

public interface BuiltInToolProvider {
    java.util.List<ToolMethod> getTools();
    String providerName();
}