package de.augmentia.quad.core.agent.runtime;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class OTelAgentTracer {
    private volatile Tracer tracer;

    @Inject
    public OTelAgentTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    /** Required no-arg constructor for CDI (e.g. tests, non-Quarkus contexts) */
    public OTelAgentTracer() {
    }

    public boolean isEnabled() {
        return tracer != null;
    }

    public Tracer getTracer() {
        return tracer;
    }

    public void trace(String event, Object... args) {
        var current = tracer;
        if (current == null) return;
        Span span = Span.current();
        span.addEvent(event);
        if (args != null) {
            for (int i = 0; i + 1 < args.length; i += 2) {
                span.setAttribute(String.valueOf(args[i]), String.valueOf(args[i + 1]));
            }
        }
    }

    public void setTracer(Tracer tracer) {
        this.tracer = tracer;
    }

    public Span startToolSpan(String toolName, String sessionId, String arguments) {
        var current = tracer;
        if (current == null) return null;
        Span span = current.spanBuilder(toolName)
                .setAttribute("tool.name", toolName)
                .setAttribute("session.id", sessionId)
                .setAttribute("tool.args", arguments)
                .startSpan();
        span.addEvent("tool.started");
        return span;
    }

    public void endToolSpan(Span span, String result, long duration) {
        if (span == null) return;
        span.setAttribute("tool.duration_ms", duration);
        span.setAttribute("tool.result", result != null ? result.substring(0, Math.min(result.length(), 1000)) : "null");
        span.addEvent("tool.finished");
        span.end();
    }

    public void failToolSpan(Span span, Exception error, long duration) {
        if (span == null) return;
        span.setAttribute("tool.duration_ms", duration);
        span.setStatus(StatusCode.ERROR, error.getMessage());
        span.recordException(error);
        span.end();
    }

    public void addEvent(String eventName, String toolName) {
        var current = tracer;
        if (current != null) {
            Span.current().addEvent(eventName);
        }
    }
}
