package de.augmentia.quad.core.observability;

import de.augmentia.quad.core.events.AgentEvent;
import de.augmentia.quad.core.events.AgentFinishedEvent;
import de.augmentia.quad.core.events.AgentStartedEvent;
import de.augmentia.quad.core.events.ModelRequestedEvent;
import de.augmentia.quad.core.events.ModelRespondedEvent;
import de.augmentia.quad.core.events.ToolExecutionFinishedEvent;
import de.augmentia.quad.core.events.ToolExecutionStartedEvent;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class MetricsHook {

    private final AgentMetrics metrics;
    private final ConcurrentMap<String, java.time.Instant> startedAt = new ConcurrentHashMap<>();

    public MetricsHook(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    public void onEvent(AgentEvent event) {
        if (event instanceof AgentStartedEvent s) {
            startedAt.put(s.sessionId(), s.timestamp());
        } else if (event instanceof AgentFinishedEvent f) {
            var start = startedAt.remove(f.sessionId());
            if (start != null) {
                metrics.getExecutionDuration().record(Duration.between(start, f.timestamp()));
            }
        } else if (event instanceof ModelRequestedEvent) {
            metrics.getLlmCalls().increment();
        } else if (event instanceof ModelRespondedEvent r) {
            metrics.getPromptTokens().record(r.inputTokens());
            metrics.getCompletionTokens().record(r.outputTokens());
        } else if (event instanceof ToolExecutionStartedEvent) {
            metrics.getToolExecutions().increment();
        } else if (event instanceof ToolExecutionFinishedEvent t && t.isError()) {
            metrics.getErrors().increment();
        }
    }
}
