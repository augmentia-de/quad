package de.augmentia.quad.core.tracing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.augmentia.quad.core.agent.runtime.tracing.JournalExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteJsonlEvents() throws Exception {
        Path file = tempDir.resolve("journal.jsonl");
        JournalExporter exporter = new JournalExporter(file);

        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("tool", "grep");
        exporter.writeEvent("session-1", "TOOL_STARTED", payload);
        exporter.write("session-1", "FINISHED", new Result("hello", 3));
        exporter.close();

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(2);

        var mapper = new ObjectMapper();
        var first = mapper.readTree(lines.get(0));
        assertThat(first.get("type").asText()).isEqualTo("TOOL_STARTED");
        assertThat(first.get("sessionId").asText()).isEqualTo("session-1");
        assertThat(first.get("payload").get("tool").asText()).isEqualTo("grep");
        assertThat(first.get("timestamp").asLong()).isPositive();

        var second = mapper.readTree(lines.get(1));
        assertThat(second.get("payload").get("value").asText()).isEqualTo("hello");
        assertThat(second.get("payload").get("count").asInt()).isEqualTo(3);
    }

    @Test
    void shouldAppendAcrossMultipleEvents() throws Exception {
        Path file = tempDir.resolve("append.jsonl");
        JournalExporter exporter = new JournalExporter(file);
        exporter.writeEvent("s", "A", null);
        exporter.writeEvent("s", "B", null);
        exporter.close();

        assertThat(Files.readAllLines(file)).hasSize(2);
    }

    @Test
    void shouldThrowWhenFileCannotBeCreated() {
        Path missing = tempDir.resolve("no-such-dir").resolve("journal.jsonl");
        assertThatThrownBy(() -> new JournalExporter(missing))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to create journal file");
    }

    @Test
    void shouldWriteLangChain4jMessagesAsReadableStrings() throws Exception {
        // Regression: ModelRequestedEvent.promptHistory contains LangChain4j-Messages
        // (SystemMessage etc.), die keine Jackson-Beans sind
        Path file = tempDir.resolve("messages.jsonl");
        JournalExporter exporter = new JournalExporter(file);

        var promptHistory = List.of(
            dev.langchain4j.data.message.SystemMessage.from("Du bist ein Agent"),
            dev.langchain4j.data.message.UserMessage.from("hello"));
        exporter.write("session-x", "ModelRequestedEvent", new PromptPayload(promptHistory));
        exporter.close();

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);

        var node = new ObjectMapper().readTree(lines.get(0));
        assertThat(node.get("type").asText()).isEqualTo("ModelRequestedEvent");
        assertThat(node.get("payload").get("promptHistory").isArray()).isTrue();
        assertThat(node.get("payload").get("promptHistory").get(0).asText())
            .contains("Du bist ein Agent");
    }

    record PromptPayload(java.util.List<dev.langchain4j.data.message.ChatMessage> promptHistory) {
    }

    record Result(String value, int count) {
    }
}
