package de.augmentia.quad.quarkus.permission;

import de.augmentia.quad.core.guards.PermissionEngine;
import de.augmentia.quad.core.guards.PermissionMode;
import de.augmentia.quad.core.hitl.checkpoint.Checkpoint;
import de.augmentia.quad.core.hitl.checkpoint.CheckpointService;
import de.augmentia.quad.core.hook.pipeline.AgentHook;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Guards tool calls (Befehlsebene) against the call session's {@link PermissionMode}.
 * Path-level access is left to {@code WorkspaceResolver} in core — this hook only
 * enforces command execution and write-mode rules. The mode is resolved per call
 * from the {@link SessionPermissionRegistry} via {@code modeResolver}. One instance
 * can be registered on all agents.
 *
 * <p>If a permission is missing (Mode-Deny) and HITL escalation is active
 * ({@code escalateMissingPermission} + a registered {@link CheckpointService}),
 * the tool call is not hard blocked but escalated to a human via a checkpoint
 * (Kafka/Email/UI are the notify channels of the {@link CheckpointService}):
 * <ul>
 *   <li>APPROVED - tool runs despite missing permission (human approval).</li>
 *   <li>REJECTED/Timeout -> tool is blocked (Cancel with feedback).</li>
 * </ul>
 * Read-only denias are excluded from escalation and always remain hard blocked.
 */
public class PermissionGuardHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(PermissionGuardHook.class);

    public static final String NAME = "permission-guard";

    private final PermissionEngine engine;
    private final SessionPermissionRegistry registry;
    private final boolean readonly;
    private final CheckpointService checkpointService;
    private final boolean escalateMissingPermission;

    public PermissionGuardHook(PermissionEngine engine, SessionPermissionRegistry registry) {
        this(engine, registry, false);
    }

    public PermissionGuardHook(PermissionEngine engine, SessionPermissionRegistry registry, boolean readonly) {
        this(engine, registry, readonly, null, false);
    }

    public PermissionGuardHook(PermissionEngine engine, SessionPermissionRegistry registry,
                               boolean readonly, CheckpointService checkpointService,
                               boolean escalateMissingPermission) {
        this.engine = engine;
        this.registry = registry;
        this.readonly = readonly;
        this.checkpointService = checkpointService;
        this.escalateMissingPermission = escalateMissingPermission;
    }

    @Override
    public String name() {
        return NAME;
    }

    private PermissionMode mode(String sessionId) {
        return registry.modeFor(sessionId);
    }

    @Override
    public HookResult beforeToolCall(HookContexts.BeforeToolCallContext ctx) {
        String tool = ctx.toolName();
        Map<String, Object> args = ctx.arguments();
        PermissionMode mode = mode(ctx.sessionId());

        switch (tool) {
            case "executeBash" -> {
                String script = str(args.get("script"), args.get("command"));
                if (readonly) {
                    return denied(mode, "executeBash in read-only mode", ctx, false);
                }
                if (!engine.canExecuteCommand(mode, script)) {
                    return denied(mode, "executeBash: " + script, ctx, true);
                }
                return HookResult.Continue.INSTANCE;
            }

            case "writeFile", "appendFile" -> {
                if (readonly) {
                    return denied(mode, "write in read-only mode", ctx, false);
                }
                String path = str(args.get("filePath"), null);
                if (path != null && !engine.canWrite(mode, path)) {
                    return denied(mode, "write to " + path, ctx, true);
                }
                return HookResult.Continue.INSTANCE;
            }

            case "multiEdit" -> {
                if (readonly) {
                    return denied(mode, "multiEdit in read-only mode", ctx, false);
                }
                Object edits = args.get("edits");
                if (edits instanceof List<?> list) {
                    for (Object edit : list) {
                        if (edit instanceof Map<?, ?> m) {
                            Object rawPath = m.get("filePath");
                            if (rawPath instanceof String p && !engine.canWrite(mode, p)) {
                                return denied(mode, "multiEdit on " + p, ctx, true);
                            }
                        }
                    }
                }
                return HookResult.Continue.INSTANCE;
            }

            default -> {
                return HookResult.Continue.INSTANCE;
            }
        }
    }

    private HookResult denied(PermissionMode mode, String detail,
                              HookContexts.BeforeToolCallContext ctx, boolean escalate) {
        if (escalate && escalateMissingPermission && checkpointService != null
                && checkpointService.isHitlEnabled()) {
            return escalateMissingPermission(ctx, mode, detail);
        }
        return new HookResult.Cancel("Operation not allowed by permission mode: " + mode + " (" + detail + ")");
    }

    private HookResult escalateMissingPermission(HookContexts.BeforeToolCallContext ctx,
                                                 PermissionMode mode, String detail) {
        Checkpoint cp;
        try {
            String arguments = ctx.arguments() != null ? ctx.arguments().toString() : "{}";
            cp = checkpointService.createCheckpoint(ctx.sessionId(), ctx.toolName(), arguments);
            log.info("missing permission ({}) for {} in session {} -> HITL-Checkpoint {} created",
                mode, ctx.toolName(), ctx.sessionId(), cp.id());
        } catch (Exception e) {
            log.warn("HITL escalation for {} failed ({}) - hard blocked",
                ctx.toolName(), e.getMessage());
            return new HookResult.Cancel("Operation not allowed by permission mode: " + mode + " (" + detail + ")");
        }

        try {
            var resolved = checkpointService.await(cp);
            if (resolved.status() == Checkpoint.Status.APPROVED) {
                log.info("HITL approval {} granted -> Tool {} runs despite missing permission",
                    cp.id(), ctx.toolName());
                return HookResult.Continue.INSTANCE;
            }
            String feedback = resolved.feedback();
            String why = feedback == null || feedback.isBlank() ? "Nicht erlaubt" : feedback;
            return new HookResult.Cancel("Operation not allowed by permission mode: " + mode
                + " (" + detail + ") — human rejected: " + why);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HookResult.Cancel("Permission escalation interrupted for " + ctx.toolName());
        }
    }

    private String str(Object value, Object fallback) {
        if (value instanceof String s) return s;
        if (fallback instanceof String f) return f;
        return null;
    }
}