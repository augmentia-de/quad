package de.augmentia.quad.core.scope;

import de.augmentia.quad.core.workflow.ExitCondition;
import de.augmentia.quad.core.workflow.LambdaExitCondition;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypedStateKeyWorkflowTest {

    private static final TypedStateKey<Integer> ITERATION_COUNT = TypedStateKey.integer("iterationCount");

    @Test
    void typedKeysWorkInLoopContext() {
        AgentScope scope = new AgentScope("test-loop");

        scope.put(ITERATION_COUNT, 1);
        assertThat((Integer) scope.get(ITERATION_COUNT)).isEqualTo(1);

        scope.put(ITERATION_COUNT, 5);
        assertThat((Integer) scope.get(ITERATION_COUNT)).isEqualTo(5);

        ExitCondition cond = LambdaExitCondition.scoreGte("eval", 0.8);
        scope.put("eval", 0.9);
        boolean result = cond.shouldExit(scope);
        assertThat(result).isEqualTo(true);

        scope.put("eval", 0.7);
        boolean result2 = cond.shouldExit(scope);
        assertThat(result2).isEqualTo(false);
    }
}
