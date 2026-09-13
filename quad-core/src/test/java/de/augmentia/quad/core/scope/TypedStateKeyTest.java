package de.augmentia.quad.core.scope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TypedStateKeyTest {

    private static final TypedStateKey<String> USER_REQUEST = TypedStateKey.string("userRequest");
    private static final TypedStateKey<Double> PLAN_SCORE = TypedStateKey.numeric("planScore");
    private static final TypedStateKey<Integer> ITERATION_COUNT = TypedStateKey.integer("iterationCount");

    @Test
    void stringKeyTypeWorks() {
        AgentScope scope = new AgentScope("test");

        scope.put(USER_REQUEST, "Fix bugs in Calculator.java");
        String value = scope.get(USER_REQUEST);

        assertThat(value).isEqualTo("Fix bugs in Calculator.java");
        assertThat(value.getClass()).isEqualTo(String.class);
    }

    @Test
    void numericKeyTypeWorks() {
        AgentScope scope = new AgentScope("test");

        scope.put(PLAN_SCORE, 0.85);
        Double value = scope.get(PLAN_SCORE);

        assertThat(value).isEqualTo(0.85);
        assertThat(value.getClass()).isEqualTo(Double.class);
    }

    @Test
    void integerKeyTypeWorks() {
        AgentScope scope = new AgentScope("test");

        scope.put(ITERATION_COUNT, 3);
        Integer value = scope.get(ITERATION_COUNT);

        assertThat(value).isEqualTo(3);
        assertThat(value.getClass()).isEqualTo(Integer.class);
    }

    @Test
    void nullValuesReturnNull() {
        AgentScope scope = new AgentScope("test");

        assertThat(scope.get(USER_REQUEST)).isNull();
        assertThat(scope.get(PLAN_SCORE)).isNull();
    }

    @Test
    void typedStateKeyConvenienceFactoryMethods() {
        TypedStateKey<String> strKey = TypedStateKey.string("name");
        assertThat(strKey.name()).isEqualTo("name");
        assertThat(strKey.type()).isEqualTo(String.class);

        TypedStateKey<Double> numKey = TypedStateKey.numeric("score");
        assertThat(numKey.name()).isEqualTo("score");
        assertThat(numKey.type()).isEqualTo(Double.class);

        TypedStateKey<Integer> intKey = TypedStateKey.integer("count");
        assertThat(intKey.name()).isEqualTo("count");
        assertThat(intKey.type()).isEqualTo(Integer.class);
    }

    @Test
    void typedKeysIntegrateWithRegularPutGet() {
        AgentScope scope = new AgentScope("test");

        scope.put("regularKey", "value1");
        scope.put(USER_REQUEST, "value2");

        assertThat((String) scope.get("regularKey")).isEqualTo("value1");
        assertThat(scope.get(USER_REQUEST)).isEqualTo((Object) "value2");
    }
}
