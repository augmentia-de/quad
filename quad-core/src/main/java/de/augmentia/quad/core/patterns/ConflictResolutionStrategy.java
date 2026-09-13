package de.augmentia.quad.core.patterns;

import de.augmentia.quad.core.scope.AgentScope;

import java.util.List;
import java.util.function.Predicate;

public interface ConflictResolutionStrategy {

    AgentInstance resolve(AgentScope scope, List<AgentInstance> candidates);

    static ConflictResolutionStrategy declarationOrder() {
        return (scope, candidates) -> candidates.get(0);
    }

    static ConflictResolutionStrategy agentOfType(Class<?> agentType, Predicate<AgentScope> condition) {
        return selectAgent(a -> a.agentType() == agentType, condition);
    }

    static ConflictResolutionStrategy agentOfType(Class<?> agentType) {
        return selectAgent(a -> a.agentType() == agentType);
    }

    static ConflictResolutionStrategy agentWithName(String agentName, Predicate<AgentScope> condition) {
        return selectAgent(a -> agentName.equals(a.agentName()), condition);
    }

    static ConflictResolutionStrategy agentWithName(String agentName) {
        return selectAgent(a -> a.agentName().equals(agentName));
    }

    static ConflictResolutionStrategy selectAgent(Predicate<AgentInstance> agentFilter, Predicate<AgentScope> condition) {
        return (scope, candidates) -> {
            if (condition.test(scope)) {
                return selectAgent(agentFilter).resolve(scope, candidates);
            }
            return null;
        };
    }

    static ConflictResolutionStrategy selectAgent(Predicate<AgentInstance> agentFilter) {
        return (scope, candidates) -> candidates.stream()
                .filter(agentFilter)
                .findFirst()
                .orElse(null);
    }

    default ConflictResolutionStrategy or(ConflictResolutionStrategy other) {
        return (scope, candidates) -> {
            AgentInstance result = this.resolve(scope, candidates);
            if (result != null) {
                return result;
            }
            return other.resolve(scope, candidates);
        };
    }

    record AgentInstance(String agentName, Class<?> agentType, List<String> argumentNames) {}
}