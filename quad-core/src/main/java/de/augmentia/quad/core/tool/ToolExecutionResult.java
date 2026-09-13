package de.augmentia.quad.core.tool;

public record ToolExecutionResult(
    String toolCallId,
    String toolName,
    String result,
    boolean isError
) {}
