package de.augmentia.quad.e2e;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.e2e.evaluation.EvaluationResult;
import de.augmentia.quad.e2e.evaluation.LlamaEvaluator;
import de.augmentia.quad.e2e.runtime.CostTracker;
import de.augmentia.quad.e2e.runtime.RetryHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.util.UUID;

@Tag("e2e")
public abstract class BaseE2ETest {
    protected LlamaEvaluator evaluator;
    protected CostTracker costTracker;
    protected RetryHandler retryHandler;
    protected String sessionId;

    @BeforeEach
    void setUp() {
        evaluator = new LlamaEvaluator();
        costTracker = new CostTracker();
        retryHandler = new RetryHandler();
        sessionId = UUID.randomUUID().toString();
        costTracker.start(sessionId);

        System.setProperty("OPENAI_API_KEY", System.getenv("OPENAI_API_KEY"));
        if (System.getenv("OPENAI_MODEL") == null) {
            System.setProperty("OPENAI_MODEL", "gpt-4o-mini");
        }
    }

    @AfterEach
    void tearDown() {
        double cost = costTracker.end(sessionId);
        if (!costTracker.withinBudget(sessionId)) {
            System.err.println("TEST EXCEEDED COST LIMIT: $" + cost);
        }
    }

    protected Agent createAgent(Class<? extends Agent> agentClass) {
        return AgentBuilder.create(agentClass)
            .withLlmFromEnv()
            .withLogging()
            .withWorkspace(Path.of("/tmp/quad-e2e-" + sessionId))
            .build();
    }

    protected EvaluationResult evaluate(String task, String expected, String actual) {
        return evaluator.evaluate(task, expected, actual);
    }

    protected boolean assertWithinBudget(double maxCost) {
        double currentCost = costTracker.getDetails(sessionId).totalCost();
        return currentCost <= maxCost;
    }
}