package de.augmentia.quad.quarkus.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.augmentia.quad.core.security.AuditEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Thread-safe JSONL file writer for audit events.
 */
class JsonlFileWriter {

    private final Path filePath;
    private final ObjectMapper mapper;

    JsonlFileWriter(Path filePath) {
        this.filePath = filePath;
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Cannot create audit directory: " + filePath.getParent(), e);
        }
    }

    synchronized void write(AuditEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            Files.writeString(filePath, json + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Audit write failed", e);
        }
    }
}
