package de.augmentia.quad.core.events;

import java.time.Instant;

/**
 * A sealed interface representing events in the agent lifecycle.
 */
public sealed interface AgentEvent
    permits AgentStartedEvent, BeforeInvocationEvent, ModelRequestedEvent,
            ToolExecutionStartedEvent, ToolExecutionFinishedEvent,
            AgentFinishedEvent, TokenEvent, AgentStateChangedEvent,
            AfterInvocationEvent {

    /** Returns the session ID associated with this event. */
    String sessionId();

    /** Returns the timestamp of this event. */
    Instant timestamp();
}
