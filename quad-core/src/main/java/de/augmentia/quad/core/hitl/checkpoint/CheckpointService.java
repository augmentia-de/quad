package de.augmentia.quad.core.hitl.checkpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private final CheckpointStore store;
    private final List<CheckpointChannel> channels = new CopyOnWriteArrayList<>();
    private final long timeoutMs;
    private final String hitlTools;
    private boolean hitlEnabled = true;

    /** Interactive UI channels (e.g. SSE popup) — receive checkpoints only when a UI is connected. */
    private final List<CheckpointChannel> uiChannels = new CopyOnWriteArrayList<>();
    /** Async notification channels (email, Kafka, ...) — receive checkpoints when no UI is connected. */
    private final List<CheckpointChannel> asyncChannels = new CopyOnWriteArrayList<>();

    private volatile UiConnectionProvider uiConnectionProvider;

    public CheckpointService(CheckpointStore store, String hitlTools, long timeoutMs) {
        this.store = store != null ? store : new InMemoryCheckpointStore();
        this.hitlTools = hitlTools != null ? hitlTools : "";
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 120_000;
    }

    public CheckpointService() {
        this(new InMemoryCheckpointStore(), System.getenv("QUAD_HITL_TOOLS"), 120_000);
    }

    public CheckpointService(String hitlTools, long timeoutMs) {
        this(new InMemoryCheckpointStore(), hitlTools, timeoutMs);
    }

    /**
     * Whether HITL is globally enabled for this service instance.
     * When disabled, {@link #requiresApproval(String)} always returns false.
     */
    public boolean isHitlEnabled() {
        return hitlEnabled;
    }

    public void setHitlEnabled(boolean hitlEnabled) {
        this.hitlEnabled = hitlEnabled;
    }

    public boolean requiresApproval(String toolName) {
        if (!hitlEnabled || hitlTools.isBlank() || toolName == null) return false;
        String target = toolName.trim();
        for (var t : hitlTools.split(",")) {
            if (t.trim().equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    public Checkpoint createCheckpoint(String sessionId, String toolName, String arguments) {
        var cp = new Checkpoint(
            sessionId + ":" + toolName + ":" + System.nanoTime(),
            sessionId, toolName, arguments
        );
        store.save(cp);
        notifyChannels(cp);
        return cp;
    }

    /**
     * Delivers the checkpoint to either the interactive UI or the async fallback channels.
     * <ul>
     *   <li>When a {@link UiConnectionProvider} is set and reports the session as connected,
     *       the UI channels are notified (popup path). If no UI channel is configured, the
     *       async channels act as a safety net.</li>
     *   <li>Otherwise the async channels are notified (email/Kafka fallback path).</li>
     *   <li>Channels registered via plain {@link #registerChannel(CheckpointChannel)} are treated
     *       as a legacy list used only when neither UI nor async channels are configured.</li>
     * </ul>
     */
    private void notifyChannels(Checkpoint cp) {
        boolean uiConnected = uiConnectionProvider != null
            && uiConnectionProvider.isUiConnected(cp.sessionId());

        List<CheckpointChannel> targets;
        if (uiConnected) {
            targets = !uiChannels.isEmpty()
                ? uiChannels
                : (!asyncChannels.isEmpty() ? asyncChannels : channels);
        } else {
            targets = !asyncChannels.isEmpty() ? asyncChannels : channels;
        }

        for (var ch : targets) {
            try {
                ch.notify(cp);
            } catch (Exception e) {
                log.warn("CheckpointChannel '{}' failed to notify checkpoint {}: {}",
                    ch.getClass().getSimpleName(), cp.id(), e.getMessage());
            }
        }
    }

    public boolean approve(String checkpointId, String feedback) {
        var cp = store.load(checkpointId).orElse(null);
        if (cp == null || cp.status() != Checkpoint.Status.PENDING) return false;
        cp.approve(feedback != null ? feedback : "");
        store.updateStatus(checkpointId, Checkpoint.Status.APPROVED, cp.feedback());
        return true;
    }

    public boolean reject(String checkpointId, String feedback) {
        var cp = store.load(checkpointId).orElse(null);
        if (cp == null || cp.status() != Checkpoint.Status.PENDING) return false;
        cp.reject(feedback != null ? feedback : "Rejected");
        store.updateStatus(checkpointId, Checkpoint.Status.REJECTED, cp.feedback());
        return true;
    }

    public Checkpoint getCheckpoint(String id) {
        return store.load(id).orElse(null);
    }

    public List<Checkpoint> getPendingCheckpoints(String sessionId) {
        return store.findPending(sessionId);
    }

    /** All pending checkpoints across all sessions — for UI/monitoring */
    public List<Checkpoint> getAllPendingCheckpoints() {
        return store.findAllPending();
    }

    public void registerChannel(CheckpointChannel channel) {
        if (channel != null) channels.add(channel);
    }

    public void unregisterChannel(CheckpointChannel channel) {
        channels.remove(channel);
    }

    /** Registers a channel that delivers to an interactive UI (popup/SSE). */
    public void registerUiChannel(CheckpointChannel channel) {
        if (channel != null) uiChannels.add(channel);
    }

    /** Registers an async notification channel (email, Kafka, ...). */
    public void registerAsyncChannel(CheckpointChannel channel) {
        if (channel != null) asyncChannels.add(channel);
    }

    public void setUiConnectionProvider(UiConnectionProvider provider) {
        this.uiConnectionProvider = provider;
    }

    public UiConnectionProvider uiConnectionProvider() {
        return uiConnectionProvider;
    }

    public List<CheckpointChannel> channels() {
        var all = new ArrayList<CheckpointChannel>(uiChannels.size() + asyncChannels.size());
        all.addAll(uiChannels);
        all.addAll(asyncChannels);
        return all;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public Checkpoint await(Checkpoint cp) throws InterruptedException {
        try {
            return cp.future().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            cp.reject("Timeout after " + timeoutMs + "ms");
            return cp;
        } catch (java.util.concurrent.ExecutionException e) {
            cp.reject("Error: " + e.getCause().getMessage());
            return cp;
        }
    }
}