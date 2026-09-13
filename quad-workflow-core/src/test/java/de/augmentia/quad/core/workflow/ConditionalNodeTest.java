package de.augmentia.quad.core.workflow;
import de.augmentia.quad.core.scope.AgentScope;

import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionalNodeTest {

    private boolean selectBranchForTest(ConditionalNode cond, AgentScope scope) {
        if (cond.conditionType() == ConditionalNode.ConditionType.PREDICATE) {
            Function<AgentScope, Boolean> pred =
                (Function<AgentScope, Boolean>) cond.conditionValue();
            return pred.apply(scope);
        } else {
            ExitCondition econd = (ExitCondition) cond.conditionValue();
            return econd.shouldExit(scope);
        }
    }

    @Test
    void predicateRoutesToTrueBranch() {
        ConditionalNode cond = new AgentWorkflowBuilder()
            .conditional("scoreCheck")
            .evaluate(scope -> ((Double) scope.get("score")) >= 0.8, "approved", "rejected")
            .branch("approved")
                .addTransform(v -> "approved result", "dummy", "output")
                .build()
            .branch("rejected")
                .addTransform(v -> "rejected result", "dummy", "output")
                .build()
            .build();

        assertThat(cond.branches().containsKey("approved")).isEqualTo(true);
        assertThat(cond.branches().containsKey("rejected")).isEqualTo(true);

        AgentScope scope = new AgentScope("test-conditional");
        scope.put("score", 0.9D);

        boolean branchMatches = selectBranchForTest(cond, scope);
        assertThat(branchMatches).isEqualTo(true);
        assertThat(cond.matchedKey()).isEqualTo("approved");
    }

    @Test
    void predicateRoutesToFalseBranch() {
        ConditionalNode cond = new AgentWorkflowBuilder()
            .conditional("scoreCheck")
            .evaluate(scope -> ((Double) scope.get("score")) >= 0.8, "approved", "needsWork")
            .branch("approved")
                .build()
            .branch("needsWork")
                .addTransform(v -> "refined plan", "old", "newPlan")
                .build()
            .build();

        assertThat(cond.branches().keySet()).containsExactlyInAnyOrder("approved", "needsWork");

        AgentScope scope = new AgentScope("test-conditional-2");
        scope.put("score", 0.5D);

        boolean branchMatches = selectBranchForTest(cond, scope);
        assertThat(branchMatches).isEqualTo(false);
    }

    @Test
    void exitConditionConditionalRoutesCorrectly() {
        ExitCondition highScore = LambdaExitCondition.scoreGte("eval", 0.8);

        ConditionalNode cond = new AgentWorkflowBuilder()
            .conditional("evalCheck")
            .evaluate(highScore, "pass", "fail")
            .branch("pass")
                .addTransform(v -> "improved", "old", "new")
                .build()
            .branch("fail")
                .addTransform(v -> "retrying", "old", "retry")
                .build()
            .build();

        AgentScope scope = new AgentScope("test-conditional-3");

        scope.put("eval", 0.9D);
        assertThat(selectBranchForTest(cond, scope)).isEqualTo(true);
        assertThat(cond.matchedKey()).isEqualTo("pass");

        scope.put("eval", 0.7D);
        assertThat(selectBranchForTest(cond, scope)).isEqualTo(false);
        assertThat(cond.notMatchedKey()).isEqualTo("fail");
    }

    @Test
    void branchBuilderChainsProperly() {
        ConditionalNode cond = new AgentWorkflowBuilder()
            .conditional("multiBranch")
            .evaluate(scope -> true, "yes", "no")
            .branch("yes")
                .addTransform(v -> "a", "a", "outA")
                .build()
            .branch("no")
                .addTransform(v -> "b", "b", "outB")
                .build()
            .build();

        assertThat(cond.branches().size()).isEqualTo(2);
        assertThat(cond.matchedKey()).isEqualTo("yes");
        assertThat(cond.notMatchedKey()).isEqualTo("no");
    }
}
