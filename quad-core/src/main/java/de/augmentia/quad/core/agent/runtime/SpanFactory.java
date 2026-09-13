package de.augmentia.quad.core.agent.runtime;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

/**
 * Manages OpenTelemetry span lifecycle for agent and LLM executions.
 * Extracted from AgentRuntime.java to separate observability from orchestration.
 */
public class SpanFactory {

    private final OTelAgentTracer tracer;

    public SpanFactory(OTelAgentTracer tracer) {
        this.tracer = tracer;
    }

    public boolean isEnabled() {
        return tracer != null && tracer.isEnabled();
    }

    /**
     * Starts a new span. Returns a SpanHandle that must be closed (via try-with-resources)
     * to end the span and close the scope.
     */
    public SpanHandle startSpan(String name, String sessionId, String prompt) {
        return startSpan(name, sessionId, prompt, null, null);
    }

    /**
     * Starts a new span with runId/stepId attributes for TelemetryStore correlation.
     */
    public SpanHandle startSpan(String name, String sessionId, String prompt, String runId, String stepId) {
        if (!isEnabled()) return SpanHandle.NOOP;

        Span span = tracer.getTracer().spanBuilder(name)
                .setAttribute("session.id", sessionId)
                .setAttribute("run.id", runId != null ? runId : "")
                .setAttribute("step.id", stepId != null ? stepId : "")
                .setAttribute("agent.prompt", prompt != null
                        ? prompt.substring(0, Math.min(prompt.length(), 200)) : "")
                .startSpan();
        Scope scope = span.makeCurrent();
        return new SpanHandle(span, scope);
    }

    /**
     * Starts an LLM-specific span with model attributes.
     */
    public SpanHandle startLlmSpan(int iteration) {
        if (!isEnabled()) return SpanHandle.NOOP;

        Span span = tracer.getTracer().spanBuilder("llm.chat")
                .setAttribute("gen_ai.system", "openai")
                .setAttribute("llm.iteration", iteration)
                .startSpan();
        Scope scope = span.makeCurrent();
        return new SpanHandle(span, scope);
    }

    /**
     * A handle to a running span that supports attribute setting and proper cleanup.
     * Use in try-with-resources to ensure span is always ended.
     */
    public static class SpanHandle implements AutoCloseable {
        static final SpanHandle NOOP = new SpanHandle(null, null);

        private final Span span;
        private final Scope scope;

        SpanHandle(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        public SpanHandle setAttribute(String key, String value) {
            if (span != null) span.setAttribute(key, value);
            return this;
        }

        public SpanHandle setAttribute(String key, long value) {
            if (span != null) span.setAttribute(key, value);
            return this;
        }

        public SpanHandle setAttribute(String key, boolean value) {
            if (span != null) span.setAttribute(key, value);
            return this;
        }

        public SpanHandle recordTokenUsage(int inputTokens, int outputTokens) {
            if (span != null) {
                span.setAttribute("gen_ai.usage.input_tokens", inputTokens);
                span.setAttribute("gen_ai.usage.output_tokens", outputTokens);
            }
            return this;
        }

        public void markCompleted(String result) {
            if (span != null) {
                span.setAttribute("stop.reason", "completed");
                if (result != null) {
                    span.setAttribute("agent.result",
                            result.substring(0, Math.min(result.length(), 500)));
                }
                span.setStatus(StatusCode.OK);
            }
        }

        public void markCancelled(String reason) {
            if (span != null) {
                span.setAttribute("stop.reason", "cancelled");
            }
        }

        public void markError(Exception e) {
            if (span != null) {
                span.setStatus(StatusCode.ERROR, e.getMessage());
                span.recordException(e);
            }
        }

        @Override
        public void close() {
            if (span != null) span.end();
            if (scope != null) scope.close();
        }
    }
}
