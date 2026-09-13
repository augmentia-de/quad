package de.augmentia.quad.core.observability;

import de.augmentia.quad.core.events.AgentEvent;
import de.augmentia.quad.core.events.AgentEventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Event listener registry that filters events before dispatch and isolates hook failures.
 */
public class EventListenerRegistry implements AgentEventListener {

    private final List<RegisteredHook> hooks = new CopyOnWriteArrayList<>();
    private volatile AgentEventListener downstream;

    public void registerHook(String name, AgentEventFilter filter, AgentEventListener hook) {
        hooks.add(new RegisteredHook(name, filter, hook));
    }

    public void registerHook(String name, AgentEventListener hook) {
        registerHook(name, e -> true, hook);
    }

    public void setDownstream(AgentEventListener downstream) {
        this.downstream = downstream;
    }

    @Override
    public void onEvent(AgentEvent event) {
        for (var hook : hooks) {
            try {
                if (hook.filter().matches(event)) {
                    hook.hook().onEvent(event);
                }
            } catch (Exception e) {
                // isolate hook failures
            }
        }
        if (downstream != null) {
            downstream.onEvent(event);
        }
    }

    public List<RegisteredHook> getHooks() {
        return List.copyOf(hooks);
    }

    public void clear() {
        hooks.clear();
    }

    public record RegisteredHook(String name, AgentEventFilter filter, AgentEventListener hook) {}
}
