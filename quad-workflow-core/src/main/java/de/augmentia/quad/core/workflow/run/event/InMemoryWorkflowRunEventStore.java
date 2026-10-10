package de.augmentia.quad.core.workflow.run.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, thread-safe {@link WorkflowRunEventStore}. Intended for tests and single-node
 * use; a JDBC-backed implementation belongs to the persistence layer.
 */
public final class InMemoryWorkflowRunEventStore implements WorkflowRunEventStore {

    private final Map<String, List<WorkflowRunEvent>> byRun = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public WorkflowRunEvent append(WorkflowRunEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        WorkflowRunEvent stored = event.withSequence(sequence.incrementAndGet());
        byRun.computeIfAbsent(stored.runId(), k -> new CopyOnWriteArrayList<>()).add(stored);
        return stored;
    }

    @Override
    public List<WorkflowRunEvent> events(String runId) {
        List<WorkflowRunEvent> events = byRun.get(runId);
        if (events == null) {
            return List.of();
        }
        List<WorkflowRunEvent> copy = new ArrayList<>(events);
        copy.sort(java.util.Comparator.comparingLong(WorkflowRunEvent::sequence));
        return copy;
    }
}
