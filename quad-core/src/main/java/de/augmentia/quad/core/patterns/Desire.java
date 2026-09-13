package de.augmentia.quad.core.patterns;

import de.augmentia.quad.core.scope.AgentScope;

import java.util.List;
import java.util.function.Predicate;

public record Desire(
    String name,
    int priority,
    Predicate<AgentScope> achievable,
    Predicate<AgentScope> satisfied,
    List<Class<?>> agentTypes
) {

    public Desire {
        if (agentTypes == null || agentTypes.isEmpty()) {
            throw new IllegalArgumentException("Desire '" + name + "' must have at least one agent type");
        }
    }

    public static Desire of(String name, int priority,
                            Predicate<AgentScope> achievable,
                            Predicate<AgentScope> satisfied,
                            Class<?>... agentTypes) {
        return new Desire(name, priority, achievable, satisfied, List.of(agentTypes));
    }

    public static Desire of(String name, int priority,
                            String achievableStateKey,
                            String satisfiedStateKey,
                            Class<?>... agentTypes) {
        return new Desire(name, priority,
                scope -> scope.containsKey(achievableStateKey),
                scope -> scope.containsKey(satisfiedStateKey),
                List.of(agentTypes));
    }
}