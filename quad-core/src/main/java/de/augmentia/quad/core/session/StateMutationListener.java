package de.augmentia.quad.core.session;

@FunctionalInterface
public interface StateMutationListener {
    void onStateChanged(String sessionId, String field, Object oldValue, Object newValue);
}