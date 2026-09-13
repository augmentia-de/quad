package de.augmentia.quad.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes audit events as JSONL (one JSON object per line).
 * Thread-safe via synchronized file writes.
 */
public class JsonlAuditLogger implements AuditLogger {

    private final Path filePath;
    private final ObjectMapper mapper;

    public JsonlAuditLogger(Path filePath) {
        this.filePath = filePath;
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Cannot create audit log directory: " + filePath.getParent(), e);
        }
    }

    @Override
    public synchronized void write(AuditEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            Files.writeString(filePath, json + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write audit event", e);
        }
    }
}
