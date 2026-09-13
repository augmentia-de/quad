package de.augmentia.quad.core.query;

import de.augmentia.quad.core.agent.runtime.EventManager;
import de.augmentia.quad.core.session.query.EventQuery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventQueryTest {

    private EventManager.StoredEvent event(String role, String content) {
        return new EventManager.StoredEvent("id", role, content, Instant.now(), false, null);
    }

    private final List<EventManager.StoredEvent> events = List.of(
            event("USER", "Hallo Welt"),
            event("ASSISTANT", "Antwort: alles gut"),
            event("TOOL", "calculate(2+2) = 4"),
            event("RUNTIME_EVENT", "error: timeout"));

    @Test
    void shouldReturnAllWithoutFilters() {
        assertThat(new EventQuery().execute(events)).hasSize(4);
    }

    @Test
    void shouldFilterByRoleRegex() {
        List<EventManager.StoredEvent> result = new EventQuery()
                .withTagRegex("USER|TOOL")
                .execute(events);

        assertThat(result).extracting(e -> e.role())
                .containsExactly("USER", "TOOL");
    }

    @Test
    void shouldFilterByContentRegexCaseInsensitive() {
        List<EventManager.StoredEvent> result = new EventQuery()
                .withContentRegex("TIMEOUT")
                .execute(events);

        assertThat(result).extracting(e -> e.content()).containsExactly("error: timeout");
    }

    @Test
    void shouldCombineRoleAndContentFilters() {
        List<EventManager.StoredEvent> result = new EventQuery()
                .withTagRegex("^TOOL$")
                .withContentRegex("calculate")
                .execute(events);

        assertThat(result).hasSize(1);
    }

    @Test
    void shouldFilterBySessionWithExtractor() {
        EventManager.StoredEvent sessionA = new EventManager.StoredEvent(
                "id-a", "USER", "in A", Instant.now(), false, null);
        EventManager.StoredEvent sessionB = new EventManager.StoredEvent(
                "id-b", "USER", "in B", Instant.now(), false, null);

        List<EventManager.StoredEvent> result = new EventQuery()
                .forSession("session-1")
                .withSessionExtractor(e -> e.id().equals("id-a") ? "session-1" : "session-2")
                .execute(List.of(sessionA, sessionB));

        assertThat(result).containsExactly(sessionA);
    }

    @Test
    void shouldHandleNullAndEmptyInput() {
        assertThat(new EventQuery().execute(null)).isEmpty();
        assertThat(new EventQuery().execute(List.of())).isEmpty();
    }
}
