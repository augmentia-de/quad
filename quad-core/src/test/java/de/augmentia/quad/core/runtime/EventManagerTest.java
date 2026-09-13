package de.augmentia.quad.core.runtime;

import de.augmentia.quad.core.agent.runtime.EventManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventManagerTest {

    private EventManager.StoredEvent event(String role, String content) {
        return new EventManager.StoredEvent(
                "id-" + content, role, content, Instant.now(), false, null);
    }

    @Test
    void shouldKeepEventsBelowThreshold() {
        EventManager manager = new EventManager(events -> "summary", 10);
        manager.add(event("USER", "a"));
        manager.add(event("ASSISTANT", "b"));

        List<EventManager.StoredEvent> result = manager.collapseIfNeeded();

        assertThat(result).hasSize(2);
        assertThat(manager.archivedEvents()).isEmpty();
    }

    @Test
    void shouldCollapseOldestEventsIntoSummary() {
        EventManager manager = new EventManager(events -> "ARCHIVED: " + events.size() + " events", 5);
        for (int i = 0; i < 10; i++) {
            manager.add(event("USER", "msg-" + i));
        }

        List<EventManager.StoredEvent> result = manager.collapseIfNeeded();

        assertThat(result).hasSize((int) (5 * 0.6) + 1);
        assertThat(result.get(0).role()).isEqualTo("SUMMARY");
        assertThat(result.get(0).content()).isEqualTo("ARCHIVED: 7 events");
        assertThat(result.get(0).isArchived()).isTrue();
        assertThat(result.get(0).summaryTag()).endsWith("-archived");

        assertThat(manager.archivedEvents()).hasSize(7);
        assertThat(manager.events()).hasSize((int) (5 * 0.6) + 1);
    }

    @Test
    void shouldNotCollapseWhenBelowSixtyPercentRemoval() {
        EventManager manager = new EventManager(events -> "summary", 100);
        for (int i = 0; i < 40; i++) {
            manager.add(event("USER", "msg-" + i));
        }

        assertThat(manager.collapseIfNeeded()).hasSize(40);
    }

    @Test
    void shouldRequirePositiveThreshold() {
        assertThatThrownBy(() -> new EventManager(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archiveThreshold");
    }
}
