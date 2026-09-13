package de.augmentia.quad.core.runtime;

import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.agent.runtime.EventManager;
import de.augmentia.quad.core.events.AgentFinishedEvent;
import de.augmentia.quad.core.events.AgentStartedEvent;
import de.augmentia.quad.core.events.ModelRequestedEvent;
import de.augmentia.quad.core.events.ToolExecutionStartedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEventPublisherIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRecordEventsInEventManager() {
        EventManager eventManager = new EventManager(10);
        AgentEventPublisher publisher = new AgentEventPublisher();
        publisher.setEventManager(eventManager);

        publisher.fire(new AgentStartedEvent("s1", Instant.now(), "hello"));
        publisher.fire(new AgentFinishedEvent("s1", Instant.now(), "world"));

        List<EventManager.StoredEvent> events = publisher.events();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).role()).isEqualTo("AGENT_STARTED");
        assertThat(events.get(0).content()).isEqualTo("hello");
        assertThat(events.get(1).role()).isEqualTo("AGENT_FINISHED");
        assertThat(events.get(1).content()).isEqualTo("world");
    }

    @Test
    void shouldMapModelAndToolEventRoles() {
        AgentEventPublisher publisher = new AgentEventPublisher();
        publisher.setEventManager(new EventManager(10));

        publisher.fire(new ModelRequestedEvent("s1", Instant.now(), List.of()));
        publisher.fire(new ToolExecutionStartedEvent("s1", Instant.now(),
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id("t1").name("grep").arguments("{}").build()));

        assertThat(publisher.events().get(0).role()).isEqualTo("MODEL_REQUESTED");
        assertThat(publisher.events().get(1).role()).isEqualTo("TOOL_STARTED");
        assertThat(publisher.events().get(1).content()).contains("grep");
    }

    @Test
    void shouldWriteJsonlWhenJournalEnabled() throws Exception {
        Path journal = tempDir.resolve("agent.jsonl");
        AgentEventPublisher publisher = new AgentEventPublisher();
        publisher.enableJournal(journal);

        publisher.fire(new AgentStartedEvent("s2", Instant.now(), "logged"));
        publisher.fire(new AgentFinishedEvent("s2", Instant.now(), "done"));

        List<String> lines = Files.readAllLines(journal);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("AgentStartedEvent");
        assertThat(lines.get(0)).contains("\"s2\"");
        assertThat(lines.get(1)).contains("AgentFinishedEvent");
    }
}
