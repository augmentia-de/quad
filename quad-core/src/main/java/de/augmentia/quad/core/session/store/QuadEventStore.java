package de.augmentia.quad.core.session.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class QuadEventStore {
    private final Map<String, Object> store = new ConcurrentHashMap<>();

    public void put(String key, Object value) {
        store.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) store.get(key);
    }

    public void remove(String key) {
        store.remove(key);
    }

    public boolean containsKey(String key) {
        return store.containsKey(key);
    }

    public void clear() {
        store.clear();
    }
}