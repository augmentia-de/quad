package de.augmentia.quad.core.session.memory;

import java.time.Instant;

/**
 * A single long-term memory entry. Framework-agnostic value object that any
 * backend (quarkus, spring-ai, dummy) can persist via {@link PersistentMemoryStore}.
 */
public class MemoryEntry {

    private String id;
    private String scope;
    private String content;
    private String summary;
    private MemoryCategory category;
    private Instant createdAt;
    private boolean sensitive;

    public MemoryEntry() {
    }

    public MemoryEntry(String id, String scope, String content, String summary,
                       MemoryCategory category, Instant createdAt, boolean sensitive) {
        this.id = id;
        this.scope = scope;
        this.content = content;
        this.summary = summary;
        this.category = category;
        this.createdAt = createdAt;
        this.sensitive = sensitive;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public MemoryCategory getCategory() { return category; }
    public void setCategory(MemoryCategory category) { this.category = category; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public boolean isSensitive() { return sensitive; }
    public void setSensitive(boolean sensitive) { this.sensitive = sensitive; }
}
