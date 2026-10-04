package de.augmentia.quad.workflow.engine.internal;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.memory.SessionMemory;
import de.augmentia.quad.core.workflow.run.WorkflowRunState;
import de.augmentia.quad.workflow.engine.node.NodeConfig;
import de.augmentia.quad.workflow.engine.port.WorkflowRunPort;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Shared, thread-safe runtime infrastructure for workflow runs: the virtual
 * thread pool, the per-run node output registries (for asynchronous deferred completion),
 * the messaging-in queues, the shared monitor object for scheduler wakeup, and the
 * reuse of token/code config. This bean is shared by scheduler and executors.
 */
public class WorkflowSupport {

    /** Defaults; identical to the previous @ConfigProperty defaultValue specifications. */
    public static final int DEFAULT_MEMORY_MAX_MESSAGES = 50;
    public static final long DEFAULT_TIMEOUT_MS = 600_000L;
    public static final long DEFAULT_MESSAGING_TIMEOUT_MS = 30_000L;

    /** Convenient constructor with the defaults - for tests and the Spring worker. */
    public WorkflowSupport(WorkflowRunPort runPort, RunStateWriter runWriter) {
        this(runPort, runWriter, DEFAULT_MEMORY_MAX_MESSAGES,
             DEFAULT_TIMEOUT_MS, DEFAULT_MESSAGING_TIMEOUT_MS);
    }

    private static final Logger log = Logger.getLogger(WorkflowSupport.class);

    private final WorkflowRunPort runPort;
    private final RunStateWriter runWriter;

    /** Shared monitor object: scheduler waits, deferred completion wakes up. */
    public final Object monitor = new Object();

    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> runOutputs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkedBlockingQueue<String>> pendingMessages = new ConcurrentHashMap<>();

    private final int memoryMaxMessages;
    private final long defaultTimeoutMs;
    private final long messagingTimeoutMs;

    /**
     * The three configuration values arrive as parameters instead of via
     * {@code @ConfigProperty}. MicroProfile Config is a container binding;
     * with it the engine would no longer allow a {@code new} and the
     * Spring Boot worker would have to rebuild MicroProfile just to pass in three numbers.
     *
     * <p>The defaults correspond to the previous {@code defaultValue} specifications.
     */
    public WorkflowSupport(final WorkflowRunPort runPort, final RunStateWriter runWriter,
                           final int memoryMaxMessages, final long defaultTimeoutMs,
                           final long messagingTimeoutMs) {
        this.runPort = runPort;
        this.runWriter = runWriter;
        this.memoryMaxMessages = memoryMaxMessages;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.messagingTimeoutMs = messagingTimeoutMs;
    }

    public ExecutorService executor() {
        return virtualExecutor;
    }

    public long defaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    /** Timeout for waiting on incoming messaging messages (messaging-in nodes). */
    public long messagingTimeoutMs() {
        return messagingTimeoutMs;
    }

    public long nodeTimeoutMs(Map<String, Object> node, long fallback) {
        String t = NodeConfig.str(node, "timeoutMs", "");
        if (!t.isBlank()) return Math.max(0, safeLong(t, 0));
        String legacy = NodeConfig.str(node, "timeout", "");
        if (legacy.isBlank()) return fallback;
        long v = safeLong(legacy, fallback);
        return v <= 0 ? 0 : v;
    }

    private long safeLong(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Result of a time-bounded call. {@code timedOut} is true if the call exceeded the
     * time limit (the future was then cancelled). On a normal completion {@code value}
     * holds the call result.
     */
    public record TimeoutResult(boolean timedOut, String value) {
        public static TimeoutResult ok(String value) { return new TimeoutResult(false, value); }
        public static TimeoutResult timedOutFlag() { return new TimeoutResult(true, null); }
    }

    /**
     * Executes a call with an optional time limit. 0 = no limit.
     *
     * <p>When the time runs out, the {@link CompletableFuture} is cancelled with {@code cancel(true)}
     * and a {@code TimeoutResult.timedOutFlag()} is returned.
     *
     * <p><b>Honest limitation (pre-spike confirmed):</b> {@code Future.cancel(true)}
     * immediately frees the scheduler/waiters (the {@code get()} throws {@link java.util.concurrent.CancellationException}),
     * interrupts interruptible blocks (e.g. {@code Thread.sleep}) and sets the interrupted flag of the virtual thread.
     * An LLM request blocked inside {@code java.net.http.HttpClient.send()} is <i>not</i> actively terminated -
     * that requires an {@code HttpClient}-readTimeout below the workflow timeout (level 4 in Docs §3.4).
     * Still, {@code cancel(true)} is an improvement over the previous behavior
     * (leaving the future running in the background): the expiring thread is interrupted
     * and expensive streams (if interruptible) are closed.
     */
    public TimeoutResult runWithTimeout(long timeoutMs, java.util.function.Supplier<String> call) {
        if (timeoutMs <= 0) return TimeoutResult.ok(call.get());
        CompletableFuture<String> fut = CompletableFuture.supplyAsync(call, virtualExecutor);
        try {
            return TimeoutResult.ok(fut.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) throw re;
            throw new RuntimeException(c);
        } catch (java.util.concurrent.TimeoutException te) {
            fut.cancel(true);
            return TimeoutResult.timedOutFlag();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fut.cancel(true);
            return TimeoutResult.timedOutFlag();
        }
    }

    public AgentSessionState newRunState() {
        AgentSessionState s = new AgentSessionState();
        s.setMemory(new SessionMemory(memoryMaxMessages));
        return s;
    }

    // ── Deferred completion (async) ─────────────────────────────

    public void registerRun(String runId, ConcurrentHashMap<String, String> outputs) {
        runOutputs.put(runId, outputs);
    }

    public void removeRun(String runId) {
        if (runId != null) runOutputs.remove(runId);
    }

    public boolean hasRun(String runId) {
        return runOutputs.containsKey(runId);
    }

    /**
     * Resolves a deferred async completion: writes the result in the run outputs,
     * marks the node as completed, wakes up the scheduler. Throws if run/node is unknown.
     */
    public void completeDeferred(String runId, String nodeId, String output) {
        if (runId == null) throw new IllegalArgumentException("runId required for deferred completion");
        ConcurrentHashMap<String, String> outs = runOutputs.get(runId);
        if (outs == null) {
            WorkflowRunState r = runPort.load(runId);
            if (r == null) throw new IllegalArgumentException("Run not found: " + runId);
            return;
        }
        synchronized (outs) {
            outs.put(nodeId, output);
        }
        WorkflowRunState run = runPort.load(runId);
        if (run != null) {
            runWriter.updateAndPersist(run, nodeId, "completed", "", output);
        }
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    // ── Messaging-In queues (thread-safe, no race) ──────────────

    public LinkedBlockingQueue<String> pendingQueue(String key) {
        return pendingMessages.computeIfAbsent(key, k -> new LinkedBlockingQueue<>());
    }

    public LinkedBlockingQueue<String> existingQueue(String key) {
        return pendingMessages.get(key);
    }

    public void deregisterQueue(String key) {
        pendingMessages.remove(key);
    }

    public boolean enqueueMessage(String key, String payload) {
        LinkedBlockingQueue<String> queue = pendingMessages.get(key);
        if (queue != null) {
            queue.offer(payload);
            log.infof("WorkflowSupport: message enqueued for key=%s (queueSize=%d)", key, queue.size());
            return true;
        }
        return false;
    }

    public boolean hasPendingHandlerFor(String key) {
        return pendingMessages.containsKey(key);
    }
}