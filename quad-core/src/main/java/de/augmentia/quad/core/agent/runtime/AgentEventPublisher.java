package de.augmentia.quad.core.agent.runtime;

import de.augmentia.quad.core.events.AgentEvent;
import de.augmentia.quad.core.events.AgentEventListener;
import de.augmentia.quad.core.events.AgentFinishedEvent;
import de.augmentia.quad.core.events.AgentStartedEvent;
import de.augmentia.quad.core.events.AgentStateChangedEvent;
import de.augmentia.quad.core.events.AfterInvocationEvent;
import de.augmentia.quad.core.events.BeforeInvocationEvent;
import de.augmentia.quad.core.events.ModelRequestedEvent;
import de.augmentia.quad.core.events.TokenEvent;
import de.augmentia.quad.core.events.ToolExecutionFinishedEvent;
import de.augmentia.quad.core.events.ToolExecutionStartedEvent;
import de.augmentia.quad.core.agent.runtime.tracing.JournalExporter;

import org.jboss.logging.Logger;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AgentEventPublisher {

    private static final Logger log = Logger.getLogger(AgentEventPublisher.class.getName());

    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    private final SubmissionPublisher<AgentEvent> publisher = new SubmissionPublisher<>();

    private final List<EventConsumer> eventConsumers = new CopyOnWriteArrayList<>();

    private EventManager eventManager;
    private JournalExporter journalExporter;

    public interface EventConsumer {
        void consume(String eventType, Object payload);
    }

    public void addEventConsumer(EventConsumer consumer) {
        eventConsumers.add(consumer);
    }

    public void fire(AgentEvent event) {
        for (var listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("Error in event listener", e);
            }
        }
        publisher.submit(event);

        if (eventManager != null) {
            eventManager.add(EventManager.StoredEvent.of(roleFor(event), contentFor(event)));
        }
        if (journalExporter != null) {
            try {
                journalExporter.write(event.sessionId(), event.getClass().getSimpleName(), event);
            } catch (Exception e) {
                log.warn("Journal write failed: " + e.getMessage());
            }
        }
    }

    public void addEventListener(AgentEventListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeEventListener(AgentEventListener listener) {
        listeners.remove(listener);
    }

    public Flow.Publisher<AgentEvent> eventStream() {
        return publisher;
    }

    public void publish(String eventType, Object payload) {
        for (EventConsumer consumer : eventConsumers) {
            try {
                consumer.consume(eventType, payload);
            } catch (Exception e) {
                log.warn("Error in event consumer: " + eventType, e);
            }
        }
    }

    public <T> void publishAsync(String eventType, T payload) {
        java.util.concurrent.CompletableFuture.runAsync(() -> publish(eventType, payload));
    }

    public void setEventManager(EventManager eventManager) {
        this.eventManager = eventManager;
    }

    public EventManager getEventManager() {
        return eventManager;
    }

    public void setJournalExporter(JournalExporter journalExporter) {
        this.journalExporter = journalExporter;
    }

    public void enableJournal(Path path) {
        this.journalExporter = new JournalExporter(path);
    }

    public List<EventManager.StoredEvent> events() {
        return eventManager != null ? eventManager.events() : List.of();
    }

    public List<EventManager.StoredEvent> archivedEvents() {
        return eventManager != null ? eventManager.archivedEvents() : List.of();
    }

    public List<EventManager.StoredEvent> collapseEvents() {
        return eventManager != null ? eventManager.collapseIfNeeded() : List.of();
    }

    private static String roleFor(AgentEvent event) {
        if (event instanceof AgentStartedEvent) return "AGENT_STARTED";
        if (event instanceof AgentFinishedEvent) return "AGENT_FINISHED";
        if (event instanceof ModelRequestedEvent) return "MODEL_REQUESTED";
        if (event instanceof ToolExecutionStartedEvent) return "TOOL_STARTED";
        if (event instanceof ToolExecutionFinishedEvent) return "TOOL_FINISHED";
        if (event instanceof TokenEvent) return "TOKEN";
        if (event instanceof AgentStateChangedEvent) return "STATE_CHANGED";
        if (event instanceof BeforeInvocationEvent) return "BEFORE_INVOCATION";
        if (event instanceof AfterInvocationEvent) return "AFTER_INVOCATION";
        return event.getClass().getSimpleName();
    }

    private static String contentFor(AgentEvent event) {
        if (event instanceof AgentStartedEvent e) return e.initialPrompt();
        if (event instanceof AgentFinishedEvent e) return e.finalAnswer();
        if (event instanceof ModelRequestedEvent e) return e.promptHistory().toString();
        if (event instanceof ToolExecutionStartedEvent e) return e.toolExecutionRequest().name()
                + " " + e.toolExecutionRequest().arguments();
        if (event instanceof ToolExecutionFinishedEvent e) return e.toolName()
                + (e.isError() ? " [ERROR] " : " ") + e.result();
        if (event instanceof TokenEvent e) return e.token();
        if (event instanceof AgentStateChangedEvent e) return e.previousPhase()
                + " -> " + e.currentPhase();
        return event.toString();
    }

    public void publishToolStarted(String sessionId, String toolName, String arguments) {
        publish("TOOL_STARTED", new ToolEvent(sessionId, toolName, arguments, System.currentTimeMillis()));
    }

    public void publishToolFinished(String sessionId, String toolName, String result, long duration) {
        publish("TOOL_FINISHED", new ToolEvent(sessionId, toolName, result, duration));
    }

    public void publishToolFailed(String sessionId, String toolName, Exception error, long duration) {
        publish("TOOL_FAILED", new ToolEvent(sessionId, toolName, error.getMessage(), duration));
    }

    public record ToolEvent(String sessionId, String toolName, String data, long timestamp) { }
}