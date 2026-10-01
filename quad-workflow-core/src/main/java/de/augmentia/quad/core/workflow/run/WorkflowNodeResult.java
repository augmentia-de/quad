package de.augmentia.quad.core.workflow.run;

/**
 * Ergebnis eines einzelnen Knotens innerhalb eines Laufs.
 *
 * <p>Vorher {@code WorkflowNodeResult}. Bewusst als Record: unveraenderlich,
 * Aenderungen ersetzen das Element in der Liste (siehe
 * {@code RunStateWriter.updateNode}).
 */
public record WorkflowNodeResult(String nodeId, String type, String title, String status,
                                 String output, String input) {}