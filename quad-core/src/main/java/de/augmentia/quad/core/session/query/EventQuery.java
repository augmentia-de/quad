package de.augmentia.quad.core.session.query;

import de.augmentia.quad.core.agent.runtime.EventManager;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Event filtering by regex on role (tag), content, and session.
 * <p>
 * Source: Python {@code src/quad/runtime/event_query.py}
 */
public class EventQuery {

    private Pattern tagPattern;
    private Pattern contentPattern;
    private String sessionId;
    private Function<EventManager.StoredEvent, String> sessionExtractor;

    public EventQuery withTagRegex(String regex) {
        this.tagPattern = Pattern.compile(regex);
        return this;
    }

    public EventQuery withContentRegex(String regex) {
        this.contentPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        return this;
    }

    public EventQuery forSession(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    /**
     * Extractor for the session ID of an event. Without an extractor,
     * the {@link #forSession} filtering is skipped.
     */
    public EventQuery withSessionExtractor(Function<EventManager.StoredEvent, String> sessionExtractor) {
        this.sessionExtractor = sessionExtractor;
        return this;
    }

    public List<EventManager.StoredEvent> execute(List<EventManager.StoredEvent> events) {
        if (events == null) {
            return List.of();
        }
        return events.stream()
                .filter(Objects::nonNull)
                .filter(e -> sessionId == null
                        || (sessionExtractor != null && sessionId.equals(sessionExtractor.apply(e))))
                .filter(e -> tagPattern == null || tagPattern.matcher(e.role()).find())
                .filter(e -> contentPattern == null || contentPattern.matcher(e.content()).find())
                .collect(Collectors.toList());
    }
}
