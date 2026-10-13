package de.augmentia.quad.core.observability;

import de.augmentia.quad.core.events.AgentFinishedEvent;
import de.augmentia.quad.core.events.AgentStartedEvent;
import de.augmentia.quad.core.events.ModelRequestedEvent;
import de.augmentia.quad.core.events.ModelRespondedEvent;
import de.augmentia.quad.core.events.ToolExecutionStartedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** MetricsHook records calls, duration, tools and the real token usage from {@link ModelRespondedEvent}. */
class MetricsHookTest {

    @Test
    void recordsCallsDurationAndRealTokenUsage() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        MetricsHook hook = new MetricsHook(metrics);

        Instant t0 = Instant.now();
        hook.onEvent(new AgentStartedEvent("s", t0, "hi"));
        hook.onEvent(new ModelRequestedEvent("s", t0, List.of()));
        hook.onEvent(new ModelRespondedEvent("s", t0.plusMillis(5), 120, 30));
        hook.onEvent(new ToolExecutionStartedEvent("s", t0.plusMillis(6), null));
        hook.onEvent(new AgentFinishedEvent("s", t0.plusMillis(10), "ok", null));

        assertThat(metrics.getLlmCalls().count()).isEqualTo(1.0);
        assertThat(metrics.getToolExecutions().count()).isEqualTo(1.0);
        assertThat(metrics.getExecutionDuration().count()).isEqualTo(1);
        assertThat(metrics.getPromptTokens().count()).isEqualTo(1);
        assertThat(metrics.getPromptTokens().totalAmount()).isEqualTo(120.0);
        assertThat(metrics.getCompletionTokens().count()).isEqualTo(1);
        assertThat(metrics.getCompletionTokens().totalAmount()).isEqualTo(30.0);
    }

    @Test
    void modelRequestedAloneDoesNotFakeTokens() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);
        MetricsHook hook = new MetricsHook(metrics);

        hook.onEvent(new ModelRequestedEvent("s", Instant.now(), List.of()));

        assertThat(metrics.getLlmCalls().count()).isEqualTo(1.0);
        assertThat(metrics.getPromptTokens().count()).isZero();
        assertThat(metrics.getCompletionTokens().count()).isZero();
    }
}
