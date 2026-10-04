package de.augmentia.quad.workflow.engine.port;

import de.augmentia.quad.core.workflow.run.WorkflowRunState;
import java.util.List;

/**
 * Persistence port for the workflow execution.
 *
 * <p>The engine deliberately knows neither Quarkus nor Panache nor JDBC. Previously
 * {@code WorkflowEngine} talked to {@code RunStore} directly — that made the
 * engine testable only within the Quarkus app and unusable for the Spring Boot worker.
 * This interface is the seam.
 *
 * <p>Implementation: {@code RunStoreAdapter} in quad-quarkus.
 *
 * <h3>Why RunState is not part of the interface</h3>
 * <p>{@code RunState} used to be an inner class of {@code RunStore} and is
 * mutated by the engine as working memory. An interface cannot return an inner class
 * without the caller ever being allowed to see it. But the engine needs
 * the state. That is why {@link WorkflowRunState} is a separate
 * framework-neutral model; the adapter maps RunState onto it.
 */
public interface WorkflowRunPort {

    /**
     * Creates a new run and returns its state.
     *
     * @param workflowId  ID of the started workflow
     * @param nodes       node IDs in execution order
     * @param initialData initial context, serialised
     */
    WorkflowRunState create(String workflowId, List<String> nodes, String initialData);

    /**
     * Loads an existing run. Returns {@code null} if unknown —
     * that is a regular case (expired run, foreign runId), not an error.
     */
    WorkflowRunState load(String runId);

    /**
     * Writes the state back. Called after every completed step
     * so that a restart continues the run at the right point.
     */
    void save(WorkflowRunState state);
}