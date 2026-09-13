package de.augmentia.quad.examples.orchestrator;

import java.util.List;

/**
 * Result of a single step in the orchestrator pipeline.
 *
 * @param stepNumber   the 1-based step number
 * @param description  the description of the step
 * @param result       the execution result
 * @param toolsUsed    the tools used by the sub-agent
 * @param success      whether the step was successful
 */
public record StepResult(
        int stepNumber,
        String description,
        String result,
        List<String> toolsUsed,
        boolean success
) {
    public static StepResult success(int step, String description, String result, List<String> tools) {
        return new StepResult(step, description, result, tools, true);
    }

    public static StepResult failure(int step, String description, String error) {
        return new StepResult(step, description, error, List.of(), false);
    }

    @Override
    public String toString() {
        String icon = success ? "OK" : "FAIL";
        return "[%s] Step %d: %s".formatted(icon, stepNumber, description);
    }
}
