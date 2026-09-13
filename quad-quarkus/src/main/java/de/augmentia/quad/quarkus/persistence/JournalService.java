package de.augmentia.quad.quarkus.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.runtime.tracing.JournalExporter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Journal service: writes agent events as JSONL (via Core-JournalExporter)
 * and provides them for REST export.
 */
@ApplicationScoped
public class JournalService {

    private static final Logger log = Logger.getLogger(JournalService.class);

    @ConfigProperty(name = "quad.journal.path", defaultValue = "${QUAD_JOURNAL_PATH:logs/journal.jsonl}")
    String journalPath;

    private final ObjectMapper mapper = new ObjectMapper();
    private volatile JournalExporter exporter;

    @PostConstruct
    void init() {
        try {
            ensureParentDirs();
            exporter = new JournalExporter(path());
            log.infof("Journal enabled: %s", path().toAbsolutePath());
        } catch (Exception e) {
            exporter = null;
            log.warnf(e, "Journal could not be opened (path %s) — export disabled", journalPath);
        }
    }

    @PreDestroy
    void shutdown() {
        if (exporter != null) {
            exporter.close();
        }
    }

    public Path path() {
        return Path.of(journalPath);
    }

    /** Returns the exporter for plugging into agent event publishers */
    public JournalExporter exporter() {
        return exporter;
    }

    public boolean isEnabled() {
        return exporter != null;
    }

    /** Reads the journal and returns the events as a list of maps */
    public List<Map<String, Object>> readEvents() {
        Path file = path();
        if (!Files.exists(file)) return List.of();
        try {
            var events = new ArrayList<Map<String, Object>>();
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = mapper.readValue(line, Map.class);
                    events.add(event);
                } catch (Exception e) {
                    log.debugf(e, "Journal line could not be parsed");
                }
            }
            return events;
        } catch (Exception e) {
            log.errorf(e, "Journal could not be read");
            return List.of();
        }
    }

    /** Clears the journal (truncates the file, keeps the exporter running) */
    public void clear() {
        if (exporter != null) {
            exporter.close();
            exporter = null;
        }
        try {
            Files.deleteIfExists(path());
            ensureParentDirs();
            exporter = new JournalExporter(path());
        } catch (Exception e) {
            exporter = null;
            log.errorf(e, "Journal could not be reset");
        }
    }

    private void ensureParentDirs() throws java.io.IOException {
        Path parent = path().toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}