package de.augmentia.quad.core.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AgentTracing {

    private static final String INSTRUMENTATION_SCOPE = "de.augmentia.quad";

    private final Tracer tracer;
    private final Map<String, Span> activeSpans = new ConcurrentHashMap<>();

    public AgentTracing(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    public Span startAgentSpan(String sessionId, String prompt) {
        var span = tracer.spanBuilder("agent.execute")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("session.id", sessionId)
            .setAttribute("prompt", truncate(prompt, 80))
            .startSpan();
        activeSpans.put(sessionId + ":agent", span);
        return span;
    }

    public Span startLlmSpan(String sessionId, int messageCount) {
        var parent = activeSpans.get(sessionId + ":agent");
        var builder = tracer.spanBuilder("llm.call")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("session.id", sessionId)
            .setAttribute("llm.messages", messageCount);
        if (parent != null) {
            builder.setParent(Context.current().with(parent));
        }
        var span = builder.startSpan();
        activeSpans.put(sessionId + ":llm", span);
        return span;
    }

    public Span startToolSpan(String sessionId, String toolName) {
        var parent = activeSpans.get(sessionId + ":agent");
        var builder = tracer.spanBuilder("tool.execute")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("session.id", sessionId)
            .setAttribute("tool.name", toolName);
        if (parent != null) {
            builder.setParent(Context.current().with(parent));
        }
        var span = builder.startSpan();
        activeSpans.put(sessionId + ":tool:" + toolName, span);
        return span;
    }

    public void endAgentSpan(String sessionId, String stopReason) {
        var span = activeSpans.remove(sessionId + ":agent");
        if (span != null) {
            span.setAttribute("stop.reason", stopReason);
            span.end();
        }
    }

    public void endLlmSpan(String sessionId, int inputTokens, int outputTokens) {
        var span = activeSpans.remove(sessionId + ":llm");
        if (span != null) {
            span.setAttribute("llm.input_tokens", inputTokens);
            span.setAttribute("llm.output_tokens", outputTokens);
            span.end();
        }
    }

    public void endToolSpan(String sessionId, String toolName, boolean isError) {
        var span = activeSpans.remove(sessionId + ":tool:" + toolName);
        if (span != null) {
            span.setAttribute("tool.error", isError);
            span.end();
        }
    }

    public Tracer getTracer() {
        return tracer;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
