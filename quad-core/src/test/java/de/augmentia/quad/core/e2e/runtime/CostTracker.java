package de.augmentia.quad.e2e.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CostTracker {
    private static final double INPUT_COST_PER_1K = 0.003;
    private static final double OUTPUT_COST_PER_1K = 0.009;
    private static final double MAX_COST_PER_TEST = 1.0;

    private final Map<String, Double> sessionCosts = new ConcurrentHashMap<>();
    private final Map<String, TestCost> sessionDetails = new ConcurrentHashMap<>();

    public void start(String sessionId) {
        sessionCosts.put(sessionId, 0.0);
        sessionDetails.put(sessionId, new TestCost(0, 0, 0, 0.0));
    }

    public void recordTokens(String sessionId, int inputTokens, int outputTokens) {
        double inputCost = (inputTokens / 1000.0) * INPUT_COST_PER_1K;
        double outputCost = (outputTokens / 1000.0) * OUTPUT_COST_PER_1K;
        double totalCost = inputCost + outputCost;

        TestCost details = sessionDetails.computeIfAbsent(sessionId, k -> new TestCost(0, 0, 0, 0.0));
        sessionDetails.put(sessionId, new TestCost(
            details.inputTokens() + inputTokens,
            details.outputTokens() + outputTokens,
            details.latencyMs() + 0,
            details.totalCost() + totalCost
        ));

        sessionCosts.merge(sessionId, totalCost, Double::sum);
    }

    public void recordLatency(String sessionId, long latencyMs) {
        TestCost details = sessionDetails.get(sessionId);
        if (details != null) {
            sessionDetails.put(sessionId, new TestCost(
                details.inputTokens(),
                details.outputTokens(),
                details.latencyMs() + latencyMs,
                details.totalCost()
            ));
        }
    }

    public double end(String sessionId) {
        Double cost = sessionCosts.remove(sessionId);
        return cost != null ? cost : 0.0;
    }

    public TestCost getDetails(String sessionId) {
        return sessionDetails.getOrDefault(sessionId, new TestCost(0, 0, 0, 0.0));
    }

    public boolean withinBudget(String sessionId) {
        return sessionCosts.getOrDefault(sessionId, 0.0) <= MAX_COST_PER_TEST;
    }

    public static TestCost getSessionDetails(String sessionId) {
        return new TestCost(0, 0, 0, 0.0);
    }

    public record TestCost(int inputTokens, int outputTokens, long latencyMs, double totalCost) {}
}