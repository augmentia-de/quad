package de.augmentia.quad.core.observability.provenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.events.AgentEvent;
import de.augmentia.quad.core.events.AgentEventListener;
import de.augmentia.quad.core.events.ToolExecutionStartedEvent;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Tracks the source (file path, URL, command, ...) each tool call operated on.
 * In-memory, keyed by session, newest first, capped per session. Registered as an
 * {@link AgentEventListener} on the core {@link AgentEventPublisher}, so it works
 * in any agent runtime.
 */
@ApplicationScoped
public class ProvenanceTracker implements AgentEventListener {

    private static final Logger log = Logger.getLogger(ProvenanceTracker.class);
    private static final int MAX_PER_SESSION = 50;
    private static final int MAX_SOURCE_LENGTH = 120;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, Deque<ProvenanceEntry>> bySession = new ConcurrentHashMap<>();
    private final Map<String, ProvenanceEntry> byRequestIdKey = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> requestIdQueues = new ConcurrentHashMap<>();

    @Inject
    AgentEventPublisher publisher;

    public ProvenanceTracker() {
    }

    @PostConstruct
    void init() {
        publisher.addEventListener(this);
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event instanceof ToolExecutionStartedEvent e) {
            String sessionId = e.sessionId();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = "default";
            }
            String toolName = e.toolExecutionRequest().name();
            String args = e.toolExecutionRequest().arguments();
            String requestId = registerExecution(sessionId);
            recordWithRequestId(sessionId, requestId, toolName, extractSource(args));
        }
    }

    // ── Legacy API ──────────────────────────────────────────────────────────

    public void record(String sessionId, String toolName, String source) {
        if (sessionId == null || toolName == null) {
            return;
        }
        Deque<ProvenanceEntry> entries = bySession.computeIfAbsent(sessionId, k -> new ConcurrentLinkedDeque<>());
        entries.addFirst(new ProvenanceEntry(toolName, firstLine(source), Instant.now()));
        while (entries.size() > MAX_PER_SESSION) {
            entries.pollLast();
        }
    }

    public String latestSource(String sessionId, String toolName) {
        Deque<ProvenanceEntry> entries = bySession.get(sessionId);
        if (entries == null) {
            return null;
        }
        for (ProvenanceEntry entry : entries) {
            if (entry.toolName().equals(toolName)) {
                return entry.source();
            }
        }
        return null;
    }

    // ── Request-aware API ───────────────────────────────────────────────────

    public String registerExecution(String sessionId) {
        String requestId = UUID.randomUUID().toString();
        requestIdQueues.computeIfAbsent(sessionId, k -> new ConcurrentLinkedDeque<>()).add(requestId);
        return requestId;
    }

    public void recordWithRequestId(String sessionId, String requestId, String toolName, String source) {
        if (sessionId == null || toolName == null) {
            return;
        }
        String key = sessionId + "|" + requestId;
        ProvenanceEntry entry = new ProvenanceEntry(toolName, firstLine(source), Instant.now());
        byRequestIdKey.put(key, entry);
        Deque<ProvenanceEntry> entries = bySession.computeIfAbsent(sessionId, k -> new ConcurrentLinkedDeque<>());
        entries.addFirst(entry);
        while (entries.size() > MAX_PER_SESSION) {
            entries.pollLast();
        }
    }

    public void record(String sessionId, String requestId, String toolName, String source) {
        recordWithRequestId(sessionId, requestId, toolName, source);
    }

    public String resolveByRequestId(String sessionId, String requestId) {
        ProvenanceEntry entry = byRequestIdKey.get(sessionId + "|" + requestId);
        return entry != null ? entry.source() : null;
    }

    // ── Read-only accessors ─────────────────────────────────────────────────

    public List<ProvenanceEntry> forSession(String sessionId) {
        Deque<ProvenanceEntry> entries = sessionId == null ? null : bySession.get(sessionId);
        if (entries == null) {
            return List.of();
        }
        return new ArrayList<>(entries);
    }

    public int count(String sessionId) {
        Deque<ProvenanceEntry> entries = sessionId == null ? null : bySession.get(sessionId);
        return entries == null ? 0 : entries.size();
    }

    public void reset(String sessionId) {
        if (sessionId != null) {
            bySession.remove(sessionId);
            requestIdQueues.remove(sessionId);
            String prefix = sessionId + "|";
            byRequestIdKey.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    // ── Source extraction ───────────────────────────────────────────────────

    public static String extractSource(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(argumentsJson);
            return firstLine(pick(root));
        } catch (Exception e) {
            return firstLine(argumentsJson);
        }
    }

    private static String pick(JsonNode root) {
        for (String key : List.of("filePath", "path", "pattern", "url", "urls", "query", "script")) {
            JsonNode value = find(root, key);
            if (value != null && !value.asText().isBlank()) {
                return truncate(value.asText());
            }
        }
        return null;
    }

    private static JsonNode find(JsonNode node, String fieldName) {
        if (node.isObject() && node.has(fieldName)) {
            return node.get(fieldName);
        }
        if (node.isObject()) {
            for (JsonNode value : node) {
                JsonNode found = find(value, fieldName);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode found = find(item, fieldName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        return truncate(text.split("\n", 2)[0]);
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > MAX_SOURCE_LENGTH ? text.substring(0, MAX_SOURCE_LENGTH) + "..." : text;
    }
}
