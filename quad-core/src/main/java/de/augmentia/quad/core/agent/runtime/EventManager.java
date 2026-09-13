package de.augmentia.quad.core.agent.runtime;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Agent event manager with archiving (collapse) for long conversations.
 * <p>
 * Source: Python {@code src/quad/events.py} (#Summary) and
 * {@code src/quad/runtime/actor.py#_collapse_oldest}
 */
public class EventManager {

    public record StoredEvent(String id, String role, String content, Instant timestamp,
                              boolean isArchived, String summaryTag) {

        public static StoredEvent of(String role, String content) {
            return new StoredEvent(UUID.randomUUID().toString(), role, content, Instant.now(),
                    false, null);
        }
    }

    private final List<StoredEvent> events = new ArrayList<>();
    private final Map<String, StoredEvent> archivedEvents = new LinkedHashMap<>();
    private final Function<List<StoredEvent>, String> summarizer;
    private final int archiveThreshold;

    public EventManager(int archiveThreshold) {
        this(events -> "[" + events.get(0).role() + "] " + events.get(0).content(), archiveThreshold);
    }

    public EventManager(ChatModel summarizerModel, int archiveThreshold) {
        this(eventsToSummarize -> summarizeWith(summarizerModel, eventsToSummarize), archiveThreshold);
    }

    public EventManager(Function<List<StoredEvent>, String> summarizer, int archiveThreshold) {
        if (summarizer == null) throw new IllegalArgumentException("summarizer must not be null");
        if (archiveThreshold <= 0) throw new IllegalArgumentException("archiveThreshold must be > 0");
        this.summarizer = summarizer;
        this.archiveThreshold = archiveThreshold;
    }

    public synchronized void add(StoredEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    public synchronized int size() {
        return events.size();
    }

    /**
     * Archives the oldest events once the threshold is exceeded.
     * Target utilization is 60% of the threshold; archived events are
     * replaced by a summary event.
     */
    public synchronized List<StoredEvent> collapseIfNeeded() {
        if (events.size() <= archiveThreshold) {
            return new ArrayList<>(events);
        }

        int toArchive = events.size() - (int) (archiveThreshold * 0.6);
        if (toArchive <= 0) {
            return new ArrayList<>(events);
        }

        List<StoredEvent> toRemove = new ArrayList<>(events.subList(0, toArchive));
        List<StoredEvent> remaining = new ArrayList<>(events.subList(toArchive, events.size()));

        String summary = summarizer.apply(toRemove);

        String summaryTag = UUID.randomUUID().toString().substring(0, 8);
        StoredEvent summaryEvent = new StoredEvent(
                summaryTag, "SUMMARY", summary, Instant.now(), true, summaryTag + "-archived");

        for (StoredEvent archived : toRemove) {
            archivedEvents.put(archived.id(), archived);
        }

        events.clear();
        events.add(summaryEvent);
        events.addAll(remaining);

        return new ArrayList<>(events);
    }

    public synchronized List<StoredEvent> archivedEvents() {
        return new ArrayList<>(archivedEvents.values());
    }

    public synchronized List<StoredEvent> events() {
        return new ArrayList<>(events);
    }

    private static String summarizeWith(ChatModel model, List<StoredEvent> eventsToSummarize) {
        StringBuilder prompt = new StringBuilder("Summarize these events briefly:\n");
        for (StoredEvent e : eventsToSummarize) {
            prompt.append("- ").append(e.role()).append(": ").append(e.content()).append("\n");
        }
        return model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from(prompt.toString())))
                .build()).aiMessage().text();
    }
}
