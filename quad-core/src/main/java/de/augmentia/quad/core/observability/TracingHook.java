package de.augmentia.quad.core.observability;

import de.augmentia.quad.core.events.AgentEvent;
import de.augmentia.quad.core.events.AgentFinishedEvent;
import de.augmentia.quad.core.events.AgentStartedEvent;
import de.augmentia.quad.core.events.ModelRequestedEvent;
import de.augmentia.quad.core.events.ToolExecutionFinishedEvent;
import de.augmentia.quad.core.events.ToolExecutionStartedEvent;

public class TracingHook {

    private final AgentTracing tracing;

    public TracingHook(AgentTracing tracing) {
        this.tracing = tracing;
    }

    public void onEvent(AgentEvent event) {
        if (event instanceof AgentStartedEvent s) {
            tracing.startAgentSpan(s.sessionId(), s.initialPrompt());
        } else if (event instanceof AgentFinishedEvent f) {
            tracing.endAgentSpan(f.sessionId(), "finished");
        } else if (event instanceof ModelRequestedEvent m) {
            tracing.startLlmSpan(m.sessionId(), m.promptHistory().size());
        } else if (event instanceof ToolExecutionStartedEvent t) {
            tracing.startToolSpan(t.sessionId(), t.toolExecutionRequest().name());
        } else if (event instanceof ToolExecutionFinishedEvent t) {
            tracing.endToolSpan(t.sessionId(), t.toolName(), t.isError());
        }
    }

    public AgentTracing tracing() {
        return tracing;
    }
}
