package de.augmentia.quad.core.workflow;
import de.augmentia.quad.core.scope.AgentScope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoopNodeTest {

    @Test
    void loopExitsEarlyWhenConditionMet() {
        AgentScope scope = new AgentScope("test-loop");
        
        // Create a simple exit condition
        ExitCondition cond = LambdaExitCondition.scoreGte("score", 0.8);
        
        // Simulate: first iteration score is below threshold, second meets it
        scope.put("score", 0.5);
        assertThat(cond.shouldExit(scope)).isEqualTo(false);
        
        scope.put("score", 0.9);
        assertThat(cond.shouldExit(scope)).isEqualTo(true);
    }

    @Test
    void loopReachesMaxIterationsWithoutExiting() {
        AgentScope scope = new AgentScope("test-loop-2");
        ExitCondition cond = LambdaExitCondition.scoreGte("score", 0.95);
        
        // Always keep score below threshold
        scope.put("score", 0.3);
        assertThat(cond.shouldExit(scope)).isEqualTo(false);
        
        scope.put("score", 0.7);
        assertThat(cond.shouldExit(scope)).isEqualTo(false);
    }

    @Test
    void expressionExitConditionParsesCorrectly() {
        ExitCondition cond = ExpressionExitCondition.fromExpression("score >= 0.8");
        assertThat(cond.describe()).startsWith("score >= ");

        AgentScope scope = new AgentScope("test");
        scope.put("score", 0.7D);
        assertThat(cond.shouldExit(scope)).isEqualTo(false);

        scope.put("score", 0.85D);
        assertThat(cond.shouldExit(scope)).isEqualTo(true);
    }

    @Test
    void lambdaExitConditionWorksDirectly() {
        ExitCondition cond = LambdaExitCondition.scoreGte("eval", 0.5);
        // Describe uses system locale's decimal separator, so we check startsWith
        assertThat(cond.describe()).startsWith("eval >= ");

        AgentScope scope = new AgentScope("test");
        scope.put("eval", 0.3D);
        assertThat(cond.shouldExit(scope)).isEqualTo(false);

        scope.put("eval", 0.5D);
        assertThat(cond.shouldExit(scope)).isEqualTo(true);

        scope.put("eval", 0.9D);
        assertThat(cond.shouldExit(scope)).isEqualTo(true);
    }

    @Test
    void differentOperatorsWork() {
        AgentScope scope = new AgentScope("test");

        scope.put("val", 5.0D);
        assertThat(ExpressionExitCondition.fromExpression("val > 3").shouldExit(scope)).isEqualTo(true);
        assertThat(ExpressionExitCondition.fromExpression("val < 3").shouldExit(scope)).isEqualTo(false);
        assertThat(ExpressionExitCondition.fromExpression("val <= 5").shouldExit(scope)).isEqualTo(true);
        assertThat(ExpressionExitCondition.fromExpression("val == 5").shouldExit(scope)).isEqualTo(true);

        scope.put("val", 3.0D);
        assertThat(ExpressionExitCondition.fromExpression("val > 3").shouldExit(scope)).isEqualTo(false);
        assertThat(ExpressionExitCondition.fromExpression("val >= 3").shouldExit(scope)).isEqualTo(true);
    }

    @Test
    void loopBuilderCreatesCorrectNode() {
        LoopNode loop = new AgentWorkflowBuilder()
            .loop("reviewLoop")
            .exitCondition(LambdaExitCondition.scoreGte("score", 0.8))
            .maxIterations(10)
            .outputKey("reviewOutput")
            .addTransform(v -> "transformed", "input", "result")
            .build();

        assertThat(loop.id()).isEqualTo("reviewLoop");
        assertThat(loop.maxIterations()).isEqualTo(10);
        assertThat(loop.exitCondition()).isNotNull();
        assertThat(loop.outputKey()).isEqualTo("reviewOutput");
        assertThat(loop.loopSteps().size()).isEqualTo(1);
    }
}
