package de.augmentia.quad.quarkus.permission;

import de.augmentia.quad.core.guards.PermissionEngine;
import de.augmentia.quad.core.guards.PermissionMode;
import de.augmentia.quad.core.hitl.checkpoint.Checkpoint;
import de.augmentia.quad.core.hitl.checkpoint.CheckpointService;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the {@link PermissionGuardHook} HITL escalation: when permission is missing
 * (mode-deny), a checkpoint is created instead of a hard {@code Cancel} and its
 * decision is awaited — APPROVED → tool runs, REJECTED → stays blocked.
 */
class PermissionGuardHitlEscalationTest {

    private static final String SESSION = "perm-escalation-session";
    private static final HookContexts.BeforeToolCallContext CALL =
        new HookContexts.BeforeToolCallContext(SESSION, "writeFile",
            Map.of("filePath", "test.txt", "content", "Hallo"));

    private final PermissionEngine engine = new PermissionEngine();
    private final SessionPermissionRegistry registry = new SessionPermissionRegistry();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void approveLetsToolRunDespiteMissingPermission() throws Exception {
        registry.setMode(SESSION, PermissionMode.PLAN);
        CheckpointService svc = new CheckpointService("", 30_000);
        var guard = new PermissionGuardHook(engine, registry, false, svc, true);

        Future<HookResult> result = executor.submit(() -> guard.beforeToolCall(CALL));
        Checkpoint cp = awaitPending(svc);
        assertTrue(svc.approve(cp.id(), "Freigabe erteilt"));

        HookResult hr = result.get(20, TimeUnit.SECONDS);
        assertInstanceOf(HookResult.Continue.class, hr, "Approver muss Tool trotz fehlender Permission zulassen");
        assertEquals(Checkpoint.Status.APPROVED, svc.getCheckpoint(cp.id()).status());
    }

    @Test
    void rejectBlocksToolWithFeedback() throws Exception {
        registry.setMode(SESSION, PermissionMode.PLAN);
        CheckpointService svc = new CheckpointService("", 30_000);
        var guard = new PermissionGuardHook(engine, registry, false, svc, true);

        Future<HookResult> result = executor.submit(() -> guard.beforeToolCall(CALL));
        Checkpoint cp = awaitPending(svc);
        assertTrue(svc.reject(cp.id(), "Nicht erlaubt"));

        HookResult hr = result.get(20, TimeUnit.SECONDS);
        HookResult.Cancel cancel = assertInstanceOf(HookResult.Cancel.class, hr);
        assertTrue(cancel.reason().contains("Nicht erlaubt"), cancel.reason());
        assertEquals(Checkpoint.Status.REJECTED, svc.getCheckpoint(cp.id()).status());
    }

    @Test
    void escalationDisabledHardBlocks() {
        registry.setMode(SESSION, PermissionMode.PLAN);
        CheckpointService svc = new CheckpointService("", 30_000);
        var guard = new PermissionGuardHook(engine, registry, false, svc, false);

        HookResult hr = guard.beforeToolCall(CALL);
        HookResult.Cancel cancel = assertInstanceOf(HookResult.Cancel.class, hr);
        assertTrue(cancel.reason().contains("permission mode"), cancel.reason());
        assertTrue(svc.getPendingCheckpoints(SESSION).isEmpty(),
            "ohne Eskalation darf kein Checkpoint entstehen");
    }

    @Test
    void readOnlyAlwaysHardBlocksEvenWithEscalation() {
        registry.setMode(SESSION, PermissionMode.AUTO);
        CheckpointService svc = new CheckpointService("", 30_000);
        var guard = new PermissionGuardHook(engine, registry, true, svc, true);

        HookResult hr = guard.beforeToolCall(CALL);
        assertInstanceOf(HookResult.Cancel.class, hr);
        assertTrue(svc.getPendingCheckpoints(SESSION).isEmpty(),
            "read-only-Denial darf nicht eskaliert werden");
    }

    @Test
    void noCheckpointServiceHardBlocks() {
        registry.setMode(SESSION, PermissionMode.PLAN);
        var guard = new PermissionGuardHook(engine, registry, false, null, true);

        HookResult hr = guard.beforeToolCall(CALL);
        assertInstanceOf(HookResult.Cancel.class, hr);
    }

    private Checkpoint awaitPending(CheckpointService svc) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            var pending = svc.getPendingCheckpoints(SESSION);
            if (!pending.isEmpty()) return pending.get(0);
            Thread.sleep(50);
        }
        return null;
    }
}