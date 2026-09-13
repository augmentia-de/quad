package de.augmentia.quad.quarkus.hitl;

import de.augmentia.quad.core.hitl.checkpoint.Checkpoint;
import de.augmentia.quad.core.hitl.checkpoint.CheckpointChannel;
import de.augmentia.quad.core.hitl.checkpoint.UiConnectionProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import jakarta.inject.Singleton;

/**
 * SSE-based CheckpointChannel for browser clients.
 * Registry-based: sessions register a {@link Consumer} for SSE-Events.
 * Simultaneously acts as {@link UiConnectionProvider}: as long as at least one SSE emitter
 * is active, the UI is considered connected (the global HITL-Overlay monitors all
 * sessions). Open tabs are tracked as a reference counter, so that closing a tab
 * does not destroy the detection of further tabs.
 */
@Singleton
public class SSEChannel implements CheckpointChannel, UiConnectionProvider {

    private final ConcurrentHashMap<String, Consumer<String>> sessionEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> sessionCounts = new ConcurrentHashMap<>();

    public void register(String sessionId, Consumer<String> emitter) {
        if (sessionId == null || emitter == null) return;
        sessionEmitters.put(sessionId, emitter);
        sessionCounts.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();
    }

    public void unregister(String sessionId) {
        if (sessionId == null) return;
        var count = sessionCounts.get(sessionId);
        if (count != null && count.decrementAndGet() <= 0) {
            sessionCounts.remove(sessionId);
            sessionEmitters.remove(sessionId);
        }
    }

    /**
     * The global HITL-Overlay monitors all sessions; each active SSE connection
     * therefore counts as "UI connected".
     */
    @Override
    public boolean isUiConnected(String sessionId) {
        var count = sessionId != null ? sessionCounts.get(sessionId) : null;
        if (count != null && count.get() > 0) return true;
        return anyUiConnected();
    }

    @Override
    public boolean anyUiConnected() {
        return sessionCounts.values().stream().anyMatch(c -> c.get() > 0);
    }

    /** Reservierte Session-Id des globalen HITL-Overlays. */
    public static final String GLOBAL_UI_SESSION = "ui";

    @Override
    public void notify(Checkpoint checkpoint) {
        var emitter = sessionEmitters.get(checkpoint.sessionId());
        if (emitter == null) {
            emitter = sessionEmitters.get(GLOBAL_UI_SESSION);
        }
        if (emitter != null) {
            var json = String.format(
                "{\"type\":\"checkpoint\",\"checkpointId\":\"%s\",\"toolName\":\"%s\",\"sessionId\":\"%s\",\"arguments\":%s}",
                escape(checkpoint.id()),
                escape(checkpoint.toolName()),
                escape(checkpoint.sessionId()),
                checkpoint.arguments() != null ? checkpoint.arguments() : "{}"
            );
            emitter.accept(json);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public Map<String, Consumer<String>> getEmitters() {
        return sessionEmitters;
    }
}