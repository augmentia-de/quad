package de.augmentia.quad.examples.patterns;

import de.augmentia.quad.core.patterns.ConflictResolutionStrategy;
import de.augmentia.quad.core.patterns.ConflictResolutionStrategy.AgentInstance;
import de.augmentia.quad.core.patterns.VotingStrategy;
import de.augmentia.quad.core.scope.AgentScope;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Dummy agent classes for demonstrating the Blackboard pattern agents */
class PerformanceAnalyst {
    public static final Class<?> TYPE = PerformanceAnalyst.class;
    public PerformanceAnalyst(String name, List<String> args) {}
}
class UsabilityExpert {
    public static final Class<?> TYPE = UsabilityExpert.class;
    public UsabilityExpert(String name, List<String> args) {}
}
class RequirementMatcher {
    public static final Class<?> TYPE = RequirementMatcher.class;
    public RequirementMatcher(String name, List<String> args) {}
}

/**
 * Demonstrates various agent patterns from the langchain4j-agentic pattern catalog.
 * <p>
 * Contains examples for:
 * <ul>
 *   <li>{@link VotingPlanner} — aggregation of opinions</li>
 *   <li>{@link BlackboardPlanner} — central knowledge exchange</li>
 *   <li>{@link BDIDesire} — priority-based goal planning</li>
 * </ul>
 * </p>
 */
public class AgentPatternsDemo {

    /**
     * Voting pattern demo: Aggregates ratings from multiple experts.
     */
    public static class VotingPlanner {

        private final VotingStrategy strategy;
        private final List<Object> votes = new ArrayList<>();

        public VotingPlanner(VotingStrategy strategy) {
            this.strategy = strategy;
        }

        public void addVote(Object vote) {
            votes.add(vote);
        }

        public Object aggregate() {
            return strategy.aggregate(votes);
        }

        public List<Object> getVotes() {
            return List.copyOf(votes);
        }
    }

    /**
     * Blackboard pattern demo: Selects next agent based on conflict resolution.
     */
    public static class BlackboardPlanner {

        private final AgentScope scope;
        private final List<AgentInstance> agents;
        private final ConflictResolutionStrategy strategy;
        private int invocationCount = 0;

        public BlackboardPlanner(AgentScope scope,
                                  List<AgentInstance> agents,
                                  ConflictResolutionStrategy strategy) {
            this.scope = scope;
            this.agents = new ArrayList<>(agents);
            this.strategy = strategy;
        }

        public AgentInstance selectNext() {
            List<AgentInstance> ready = agents.stream()
                    .filter(agent -> canActivate(agent))
                    .toList();

            if (ready.isEmpty()) {
                return null;
            }

            return strategy.resolve(scope, ready);
        }

        private boolean canActivate(AgentInstance agent) {
            return agent.argumentNames().stream()
                    .allMatch(scope::containsKey);
        }

        public int getInvocationCount() {
            return invocationCount;
        }

        public AgentScope getScope() {
            return scope;
        }
    }

    /**
     * BDI pattern demo: Manages desire-based planning.
     */
    public static class BDIDesire {

        private final String name;
        private final int priority;
        private final Predicate<AgentScope> achievable;
        private final Predicate<AgentScope> satisfied;
        private final List<Class<?>> agentSequence;

        public BDIDesire(String name, int priority,
                         Predicate<AgentScope> achievable,
                         Predicate<AgentScope> satisfied,
                         List<Class<?>> agentSequence) {
            this.name = name;
            this.priority = priority;
            this.achievable = achievable;
            this.satisfied = satisfied;
            this.agentSequence = List.copyOf(agentSequence);
        }

        public String getName() { return name; }
        public int getPriority() { return priority; }
        public boolean isAchievable(AgentScope scope) { return achievable.test(scope); }
        public boolean isSatisfied(AgentScope scope) { return satisfied.test(scope); }
        public List<Class<?>> getAgentSequence() { return agentSequence; }

        @Override
        public String toString() {
            return "Desire{name='%s', priority=%d}".formatted(name, priority);
        }
    }

    // --- Demo methods ---

    /**
     * Demonstrates voting with different strategies.
     */
    public static void demoVoting() {
        System.out.println("\n=== VOTING PATTERN DEMO ===");

        AgentScope scope = new AgentScope("voting-demo");
        scope.put("historicalScore", 7.5);
        scope.put("technicalScore", 8.2);
        scope.put("culturalFitScore", 6.8);

        System.out.println("Voting: Bewertung Kandidat");

        // Majority voting
        VotingPlanner majority = new VotingPlanner(VotingStrategy.majority());
        majority.addVote("TIEFE EMPFOHLUNGEN");
        majority.addVote("INTERVIEW EINLADEN");
        majority.addVote("TIEFE EMPFOHLUNGEN");
        System.out.println("Majority result: " + majority.aggregate());

        // Average voting (for numerical ratings)
        VotingPlanner average = new VotingPlanner(VotingStrategy.average());
        average.addVote(7.5);
        average.addVote(8.2);
        average.addVote(6.8);
        System.out.println("Average score: " + String.format("%.2f", average.aggregate()));

        // Highest voting
        VotingPlanner highest = new VotingPlanner(VotingStrategy.highest());
        highest.addVote("MITTLIG");
        highest.addVote("HOCH");
        highest.addVote("SEHR HOCH");
        System.out.println("Highest rating: " + highest.aggregate());
    }

    /**
     * Demonstrates blackboard with conflict resolution.
     */
    public static void demoBlackboard() {
        System.out.println("\n=== BLACKBOARD PATTERN DEMO ===");

        AgentScope scope = new AgentScope("blackboard-demo");
        scope.put("rawData", "Kundenfeedback: Produkt ist langsam");
        scope.put("requirements", new String[]{"Performance", "Benutzerfreundlichkeit"});

        List<AgentInstance> agents = List.of(
                new AgentInstance("PerformanceAnalyst", PerformanceAnalyst.class, List.of("rawData")),
                new AgentInstance("UsabilityExpert", UsabilityExpert.class, List.of("rawData")),
                new AgentInstance("RequirementMatcher", RequirementMatcher.class, List.of("rawData", "requirements"))
        );

        ConflictResolutionStrategy strategy = ConflictResolutionStrategy
                .agentOfType(PerformanceAnalyst.class)
                .or(ConflictResolutionStrategy.agentOfType(UsabilityExpert.class))
                .or(ConflictResolutionStrategy.agentOfType(RequirementMatcher.class));

        BlackboardPlanner planner = new BlackboardPlanner(scope, agents, strategy);

        System.out.println("Blackboard: Select next analyst");
        System.out.println("Scope has keys: " + scope.snapshot().keySet());

        int step = 1;
        while (true) {
            AgentInstance next = planner.selectNext();
            if (next == null) {
                System.out.println("Kein aktivierbarer Agent mehr — Blackboard quiescent");
                break;
            }

            System.out.println("Schritt %d: %s ist bereit".formatted(step, next.agentName()));

            // Simulate agent execution
            simulateAgentExecution(next, scope);

            if (scope.containsKey("recommendation")) {
                System.out.println("Ziel erreicht: " + scope.get("recommendation"));
                break;
            }
            step++;
            if (step > 5) break;
        }
    }

    /**
     * Demonstrates BDI with desire prioritization.
     */
    public static void demoBDI() {
        System.out.println("\n=== BDI PATTERN DEMO ===");

        AgentScope scope = new AgentScope("bdi-demo");
        scope.put("emergency", false);
        scope.put("routineTaskCount", 3);
        scope.put("criticalTaskCompleted", false);

        List<BDIDesire> desires = List.of(
                new BDIDesire(
                        "Notfallbehandlung",
                        100,
                        s -> s.containsKey("emergency") && (Boolean) s.get("emergency"),
                        s -> s.containsKey("emergencyHandled"),
                        List.of(EmergencyHandler.class)
                ),
                new BDIDesire(
                        "Kritische Aufgaben",
                        75,
                        s -> s.containsKey("criticalTask"),
                        s -> s.containsKey("criticalTaskCompleted"),
                        List.of(CriticalTaskHandler.class)
                ),
                new BDIDesire(
                        "Routine-Aufgaben",
                        50,
                        s -> s.containsKey("routineTaskCount"),
                        s -> s.containsKey("routineComplete"),
                        List.of(RoutineTaskHandler.class)
                )
        );

        System.out.println("BDI: Available desires sorted by priority:");
        desires.stream()
                .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
                .forEach(d -> System.out.println("  " + d));

        // Simuliere Deliberation
        BDIDesire currentDesire = desires.stream()
                .filter(d -> d.isAchievable(scope))
                .max((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                .orElse(null);

        if (currentDesire != null) {
            System.out.println("\nAktuelle Intention: " + currentDesire.getName());
            System.out.println("Agenten: " + currentDesire.getAgentSequence().stream()
                    .map(Class::getSimpleName)
                    .toList());
        }

        // Setze kritische Aufgabe als erledigt
        scope.put("criticalTaskCompleted", true);
        System.out.println("\nNach Erledigung kritischer Aufgabe:");
        desires.stream()
                .filter(d -> d.isAchievable(scope) && !d.isSatisfied(scope))
                .max((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                .ifPresent(d -> System.out.println("Next intention: " + d.getName()));
    }

    private static void simulateAgentExecution(AgentInstance agent, AgentScope scope) {
        System.out.println("  >> Agent '" + agent.agentName() + "' is being executed...");
        // Simulate agent output
        switch (agent.agentName()) {
            case "PerformanceAnalyst" -> scope.put("performanceRating", "GUT");
            case "UsabilityExpert" -> scope.put("usabilityRating", "DEUTSCH");
            case "RequirementMatcher" -> scope.put("recommendation", "TIEFE EMPFOHLUNDEN");
        }
    }

    // --- Dummy handler classes for BDI ---

    static class EmergencyHandler {}
    static class CriticalTaskHandler {}
    static class RoutineTaskHandler {}

    // --- Demo start ---

    public static void main(String[] args) {
        demoVoting();
        demoBlackboard();
        demoBDI();

        System.out.println("\n=== DEMO ABGESCHLOSSEN ===");
    }
}