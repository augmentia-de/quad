package de.augmentia.quad.core.workflow.run;

/**
 * Result of a single node within a run.
 *
 * <p>Previously {@code WorkflowNodeResult}. Deliberately a record: immutable,
 * changes replace the element in the list (see
 * {@code RunStateWriter.updateNode}).
 */
public record WorkflowNodeResult(String nodeId, String type, String title, String status,
                                 String output, String input) {}