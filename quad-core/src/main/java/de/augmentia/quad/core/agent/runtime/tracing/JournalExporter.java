package de.augmentia.quad.core.agent.runtime.tracing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Journal exporter for persisting trace events in JSONL format.
 * <p>
 * Source: Python {@code src/quad/tracing/_litellm_journal.py}, {@code _otlp_file_exporter.py}
 */
public class JournalExporter {

    private final PrintWriter writer;
    private final ReentrantLock lock = new ReentrantLock();
    private final ObjectMapper mapper;

    public JournalExporter(Path filePath) {
        var module = new SimpleModule("quad-journal");
        // Serialize LangChain4j messages as readable strings (no getter beans)
        module.addSerializer(SystemMessage.class, new ToStringSerializer());
        module.addSerializer(UserMessage.class, new ToStringSerializer());
        module.addSerializer(AiMessage.class, new ToStringSerializer());
        module.addSerializer(ToolExecutionResultMessage.class, new ToStringSerializer());

        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(module)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

        try {
            this.writer = new PrintWriter(new FileWriter(filePath.toFile(), true), true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create journal file: " + filePath, e);
        }
    }

    public synchronized void writeEvent(String sessionId, String type, ObjectNode payload) {
        ObjectNode event = mapper.createObjectNode();
        event.put("timestamp", System.currentTimeMillis());
        event.put("sessionId", sessionId);
        event.put("type", type);
        if (payload != null) {
            event.set("payload", payload);
        }
        writeJson(event);
    }

    /**
     * Writes an arbitrary object as event payload.
     * Non-(fully) serializable payloads fall back to toString() —
     * a journal issue must never break the agent run.
     */
    public synchronized void write(String sessionId, String type, Object payload) {
        ObjectNode event = mapper.createObjectNode();
        event.put("timestamp", System.currentTimeMillis());
        event.put("sessionId", sessionId);
        event.put("type", type);
        event.set("payload", toNode(payload));
        writeJson(event);
    }

    private JsonNode toNode(Object payload) {
        try {
            return mapper.valueToTree(payload);
        } catch (Exception e) {
            return mapper.getNodeFactory().textNode(String.valueOf(payload));
        }
    }

    private void writeJson(ObjectNode event) {
        lock.lock();
        try {
            writer.println(mapper.writeValueAsString(event));
        } catch (IOException e) {
            throw new RuntimeException("Failed to write journal event", e);
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        writer.close();
    }
}
