package de.augmentia.quad.core.workflow.transfer;

/**
 * Thrown when a v2 workflow cannot be published — for example an unresolvable
 * {@code agentRef} or a schema violation. Publishing must <b>fail closed</b>.
 */
public class QuadWorkflowPublishException extends RuntimeException {

    public QuadWorkflowPublishException(String message) {
        super(message);
    }

    public QuadWorkflowPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
