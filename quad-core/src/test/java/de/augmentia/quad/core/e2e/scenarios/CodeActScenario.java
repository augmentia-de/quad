package de.augmentia.quad.e2e.scenarios;

import de.augmentia.quad.e2e.BaseE2ETest;
import de.augmentia.quad.e2e.TestAgent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class CodeActScenario extends BaseE2ETest {

    @Test
    void shouldExecuteCodeActAndVerifyFileRead() throws Exception {
        Path workspace = Path.of("/tmp/test-workspace-" + sessionId);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("test.txt"), "Hello World\nLine 2\nLine 3");

        var agent = createAgent(TestAgent.class);

        String task = "Read the test.txt file and count how many lines it has";
        String expected = "The file has 3 lines.";

        var result = retryHandler.executeWithRetry(() -> {
            try {
                return agent.run(task);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "CodeAct execution");

        var evaluation = evaluate(task, expected, result);

        System.out.println("""
            
            ╔══════════════════════════════════════════════════════════╗
            ║                    CODEACT TEST RESULTS                  ║
            ╠══════════════════════════════════════════════════════════╣
            ║ Task:         %s                                      ║
            ║ Expected:     %s                                      ║
            ║ Actual:       %s                                      ║
            ║ Score:        %d/5                                    ║
            ║ Feedback:     %s                                      ║
            ║ Cost:         %.4f USD                                ║
            ║ Latency:      %d ms                                  ║
            ╚══════════════════════════════════════════════════════════╝
            
            """.formatted(
                task, expected, result, 
                evaluation.score(), 
                evaluation.feedback(),
                costTracker.getDetails(sessionId).totalCost(),
                evaluation.latencyMs()));

        assertTrue(evaluation.score() >= 4, "Expected good score but got: " + evaluation.feedback());
        assertTrue(assertWithinBudget(0.10), "Cost exceeded budget");
    }

    @Test
    void shouldHandleAndRecoverFromToolErrors() throws Exception {
        var agent = createAgent(TestAgent.class);

        String task = "Read /nonexistent/file.txt";

        String result;
        Exception capturedError = null;
        
        try {
            result = agent.run(task);
        } catch (Exception e) {
            capturedError = e;
            result = "Tool executed but failed as expected due to missing file";
        }

        var evaluation = evaluate(task, "Properly handles error", result);

        assertNotNull(capturedError, "Expected an error for non-existent file");
    }
}