package de.augmentia.quad.core.session.memory;

import java.util.List;

/**
 * SPI for long-term memory persistence. Lives in the framework-agnostic core so
 * every agent runtime can inject memory; concrete backends (JDBC, in-memory)
 * implement it.
 */
public interface PersistentMemoryStore {

    /** Stores an entry (upsert by id). */
    void remember(MemoryEntry item);

    /** All entries for a scope, newest first. */
    List<MemoryEntry> findByScope(String scope);

    /** All entries across scopes. */
    List<MemoryEntry> listAll();

    /** Updates the content of an entry, keeping the existing summary when present. */
    void updateContent(String id, String content);

    /** Deletes all entries. */
    void deleteAll();

    /** Deletes a single entry. */
    void forget(String id);

    /**
     * Formats a scope's memory as a single compact injection block for a system
     * prompt, or empty string when the scope has no entries.
     */
    default String formatForInjection(String scope) {
        List<MemoryEntry> entries = findByScope(scope);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry e : entries) {
            sb.append('[').append(e.getCategory()).append("] ")
              .append(e.getSummary() != null ? e.getSummary() : e.getContent())
              .append('\n');
        }
        return sb.toString();
    }
}
