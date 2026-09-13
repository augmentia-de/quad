package de.augmentia.quad.core.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.annotation.Idempotent;
import de.augmentia.quad.core.events.ToolExecutionFinishedEvent;
import de.augmentia.quad.core.events.ToolExecutionStartedEvent;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookRegistry;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import de.augmentia.quad.core.security.AuditEvent;
import de.augmentia.quad.core.security.AuditLogger;
import de.augmentia.quad.core.security.NoSecurityContext;
import de.augmentia.quad.core.security.SecurityContext;
import de.augmentia.quad.core.security.ToolGuard;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ToolExecutor {

    private final IdempotencyStore idempotencyStore;
    private final OTelAgentTracer tracer;
    private final AgentEventPublisher eventPublisher;
    private final HookRegistry hookRegistry;
    private final SagaAgentInterceptor saga;
    private final ObjectMapper objectMapper;
    private final ToolGuard toolGuard;
    private final AuditLogger auditLogger;

    @Inject
    public ToolExecutor(IdempotencyStore idempotencyStore, OTelAgentTracer tracer,
                        AgentEventPublisher eventPublisher, HookRegistry hookRegistry) {
        this(idempotencyStore, tracer, eventPublisher, hookRegistry,
            ToolGuard.ALLOW_ALL, AuditLogger.NOOP);
    }

    public ToolExecutor(IdempotencyStore idempotencyStore, OTelAgentTracer tracer,
                        AgentEventPublisher eventPublisher, HookRegistry hookRegistry,
                        ToolGuard toolGuard, AuditLogger auditLogger) {
        this.idempotencyStore = idempotencyStore;
        this.tracer = tracer;
        this.eventPublisher = eventPublisher;
        this.hookRegistry = hookRegistry;
        this.saga = new SagaAgentInterceptor();
        this.objectMapper = new ObjectMapper();
        this.toolGuard = toolGuard;
        this.auditLogger = auditLogger;
    }

    public ToolExecutor(IdempotencyStore idempotencyStore, OTelAgentTracer tracer,
                        AgentEventPublisher eventPublisher) {
        this(idempotencyStore, tracer, eventPublisher, new HookRegistry());
    }

    /**
     * Execute a tool with security context (new API).
     */
    public ToolResult execute(ToolMethod tool, String jsonArguments,
                              AgentSessionState state, SecurityContext ctx) {
        if (ctx == null) ctx = NoSecurityContext.get();
        String toolName = tool.spec().name();
        String sessionId = state != null ? state.getSessionId() : "";
        String correlationId = Optional.ofNullable(ctx.claims().get("correlationId")).orElse("");

        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Tool execution aborted: thread interrupted before executing " + toolName);
        }

        // --- Tool RBAC Check ---
        if (toolGuard != null && !toolGuard.isAllowed(toolName, ctx)) {
            auditLogger.write(AuditEvent.accessDenied(
                Instant.now(), ctx.userId(), ctx.roles(), sessionId,
                toolName, "insufficient roles", correlationId));
            return ToolResult.error("Access denied: insufficient roles for " + toolName);
        }

        if (eventPublisher != null) {
            eventPublisher.fire(new ToolExecutionStartedEvent(sessionId, Instant.now(),
                ToolExecutionRequest.builder()
                    .id(toolName + "-" + System.nanoTime())
                    .name(toolName)
                    .arguments(jsonArguments)
                    .build()));
        }

        Map<String, Object> args = parseArguments(jsonArguments);

        if (hookRegistry != null) {
            var before = hookRegistry.triggerBeforeToolCall(
                new HookContexts.BeforeToolCallContext(sessionId, toolName, args));
            if (before instanceof HookResult.Cancel c) {
                return ToolResult.error("Tool call cancelled: " + c.reason());
            }
        }

        boolean isIdempotent = isIdempotentTool(tool);
        String idempotencyKey = null;

        if (isIdempotent && state != null) {
            idempotencyKey = idempotencyStore.computeKey(sessionId, toolName, jsonArguments);
            Optional<String> cachedResult = Optional.ofNullable(idempotencyStore.get(idempotencyKey));
            if (cachedResult.isPresent()) {
                tracer.addEvent("idempotent_replay", toolName);
                return ToolResult.success(cachedResult.get());
            }
        }

        Span span = tracer.startToolSpan(toolName, sessionId, jsonArguments);
        long startTime = System.currentTimeMillis();

        try {
            eventPublisher.publishToolStarted(sessionId, toolName, jsonArguments);

            ToolResult result = tool.execute(jsonArguments, state);

            state.dispatchMutationEvents();

            if (isIdempotent && idempotencyKey != null) {
                idempotencyStore.put(idempotencyKey, result.text());
            }

            long duration = System.currentTimeMillis() - startTime;
            tracer.endToolSpan(span, result.text(), duration);
            eventPublisher.publishToolFinished(sessionId, toolName, result.text(), duration);
            eventPublisher.fire(new ToolExecutionFinishedEvent(sessionId, Instant.now(), toolName, false, result.text()));

            // --- Audit: tool call success ---
            auditLogger.write(AuditEvent.toolCall(
                Instant.now(), ctx.userId(), ctx.roles(), sessionId,
                toolName, jsonArguments, result.text(), false, duration,
                correlationId, ctx.claims().get("jti")));

            return finalizeResult(state, toolName, result, false);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            tracer.failToolSpan(span, e, duration);
            eventPublisher.publishToolFailed(sessionId, toolName, e, duration);
            eventPublisher.fire(new ToolExecutionFinishedEvent(sessionId, Instant.now(), toolName, true, e.getMessage()));

            // Saga: on partial failure, execute registered compensations
            if (state != null) {
                state.markSagaFailed();
                saga.onAfterToolExecution(state, toolName, false);
            }

            // --- Audit: tool call error ---
            auditLogger.write(AuditEvent.toolCall(
                Instant.now(), ctx.userId(), ctx.roles(), sessionId,
                toolName, jsonArguments, e.getMessage(), true, duration,
                correlationId, ctx.claims().get("jti")));

            String errorResult = String.format("Tool Execution Error [%s]: %s", toolName, e.getMessage());
            return finalizeResult(state, toolName, ToolResult.error(errorResult), true);
        }
    }

    /**
     * Execute a tool without explicit security context (backward-compatible API).
     * Uses security context from AgentSessionState if available.
     */
    public ToolResult execute(ToolMethod tool, String jsonArguments, AgentSessionState state) {
        SecurityContext ctx = (state != null && state.getSecurityContext() != null)
            ? state.getSecurityContext()
            : NoSecurityContext.get();
        return execute(tool, jsonArguments, state, ctx);
    }

    private ToolResult finalizeResult(AgentSessionState state, String toolName, ToolResult result, boolean isError) {
        if (hookRegistry == null) return result;
        var after = hookRegistry.triggerAfterToolCall(
            new HookContexts.AfterToolCallContext(state.getSessionId(), toolName, result.text(), isError), result.text());
        if (after instanceof HookResult.Modify<?> m) {
            return ToolResult.success((String) m.value());
        }
        if (after instanceof HookResult.Cancel c) {
            return ToolResult.error("Tool call cancelled: " + c.reason());
        }
        return result;
    }

    private Map<String, Object> parseArguments(String jsonArguments) {
        if (jsonArguments == null || jsonArguments.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(jsonArguments, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private boolean isIdempotentTool(ToolMethod tool) {
        try {
            var method = tool.getClass().getMethod("spec");
            return method.isAnnotationPresent(Idempotent.class);
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    public HookRegistry getHookRegistry() {
        return hookRegistry;
    }
}
