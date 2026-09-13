package de.augmentia.quad.core.scope;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Typed, shared scope for agent workflows and patterns.
 * <p>
 * Agents write their outputs into the scope under keys; subsequent steps read
 * them back directly ({@link #get(String)}) or extract specific fields via
 * {@link #extract(String, String)}.
 */
public class AgentScope {

    private final String workflowId;
    private final Map<String, Object> store = new ConcurrentHashMap<>();

    public AgentScope(String workflowId) {
        this.workflowId = workflowId == null || workflowId.isBlank()
            ? "workflow-" + System.currentTimeMillis()
            : workflowId;
    }

    public String workflowId() { return workflowId; }

    public AgentScope put(String key, Object value) {
        if (value != null) {
            store.put(key, value);
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) store.get(key);
    }

    public boolean containsKey(String key) {
        return store.containsKey(key);
    }

    public Object remove(String key) {
        return store.remove(key);
    }

    public Map<String, Object> snapshot() {
        return Map.copyOf(store);
    }

    /** Extracts a sub-field path from the value stored under {@code key}. */
    public <T> T extract(String key, String fieldPath) {
        return FieldExtractor.extract(store.get(key), fieldPath);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(TypedStateKey<T> key) {
        Object value = store.get(key.name());
        if (value == null) return null;
        if (!key.type().isInstance(value)) {
            throw new ClassCastException(
                "Expected " + key.type().getSimpleName() + " for key '" + key.name()
                + "', but got " + value.getClass().getSimpleName());
        }
        return (T) value;
    }

    public <T> void put(TypedStateKey<T> key, T value) {
        if (value != null && !key.type().isInstance(value)) {
            throw new ClassCastException(
                "Cannot put " + value.getClass().getSimpleName()
                + " into TypedStateKey expecting " + key.type().getSimpleName());
        }
        store.put(key.name(), value);
    }

    public Map<String, Object> templateVariables() {
        Map<String, Object> values = new LinkedHashMap<>(store);
        for (var entry : store.entrySet()) {
            if (entry.getValue() instanceof Record record) {
                for (RecordComponent rc : record.getClass().getRecordComponents()) {
                    Object field = FieldExtractor.extract(record, rc.getName());
                    values.put(entry.getKey() + "." + rc.getName(), field);
                }
            }
        }
        return values;
    }
}
