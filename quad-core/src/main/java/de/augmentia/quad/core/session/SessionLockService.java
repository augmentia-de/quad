package de.augmentia.quad.core.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionLockService {
    private final Map<String, LockEntry> locks = new ConcurrentHashMap<>();

    public boolean acquireLock(String sessionId, String ownerToken, int ttlSeconds) {
        LockEntry existing = locks.get(sessionId);
        if (existing != null && !existing.isExpired()) {
            return false;
        }
        locks.put(sessionId, new LockEntry(ownerToken, System.currentTimeMillis() + ttlSeconds * 1000L));
        return true;
    }

    public boolean releaseLock(String sessionId, String ownerToken) {
        LockEntry entry = locks.get(sessionId);
        if (entry != null && entry.ownerToken.equals(ownerToken)) {
            locks.remove(sessionId);
            return true;
        }
        return false;
    }

    public boolean isLocked(String sessionId) {
        LockEntry entry = locks.get(sessionId);
        return entry != null && !entry.isExpired();
    }

    public String getLockOwner(String sessionId) {
        LockEntry entry = locks.get(sessionId);
        return entry != null && !entry.isExpired() ? entry.ownerToken : null;
    }

    public void heartbeat(String sessionId, String ownerToken) {
        LockEntry entry = locks.get(sessionId);
        if (entry != null && entry.ownerToken.equals(ownerToken)) {
            entry.refresh(System.currentTimeMillis() + 30000L);
        }
    }

    private static record LockEntry(String ownerToken, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
        void refresh(long newExpiresAt) {}
    }
}