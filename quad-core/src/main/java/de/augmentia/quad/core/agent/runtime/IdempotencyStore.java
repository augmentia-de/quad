package de.augmentia.quad.core.agent.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class IdempotencyStore {
    private final Map<String, String> store = new ConcurrentHashMap<>();

    public String get(String key) {
        return store.get(key);
    }

    public void put(String key, String value) {
        store.put(key, value);
    }

    public void remove(String key) {
        store.remove(key);
    }

    public void clear() {
        store.clear();
    }

    public boolean containsKey(String key) {
        return store.containsKey(key);
    }

    public String computeKey(String sessionId, String toolName, String jsonArguments) {
        return sessionId + ":" + toolName + ":" + jsonArguments.hashCode();
    }
}