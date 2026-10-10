package de.augmentia.quad.core.workflow.transfer;

/**
 * Thrown when a workflow document cannot be converted between schema generations
 * (for example a v1 node that has no faithful v2 representation).
 *
 * <p>Deliberately fail-closed: a conversion that cannot be represented is rejected
 * instead of silently dropping nodes or re-typing them.</p>
 */
public class QuadWorkflowConversionException extends RuntimeException {

    public QuadWorkflowConversionException(String message) {
        super(message);
    }

    public QuadWorkflowConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
