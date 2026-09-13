package de.augmentia.quad.core.agent.runtime;

import de.augmentia.quad.core.annotation.Idempotent;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTest {

    @Test
    void idempotent_tool_replays_cached_result() throws Exception {
        var calls = new AtomicInteger();
        ToolMethod idem = new ToolMethod() {
            @Override
            @Idempotent
            public ToolSpecification spec() {
                return ToolSpecification.builder().name("idempotent").description("").build();
            }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
                calls.incrementAndGet();
                return ToolResult.success("result-" + calls.get());
            }
        };

        AgentSessionState state = new AgentSessionState();
        // Mit geheftetem EventPublisher → keine NPE in publish*
        var executor = new ToolExecutor(new IdempotencyStore(), new OTelAgentTracer(), new AgentEventPublisher());

        ToolResult first = executor.execute(idem, "{}", state);
        ToolResult second = executor.execute(idem, "{}", state);

        assertEquals("result-1", first.text());
        assertEquals("result-1", second.text()); // cached, not re-run
        assertEquals(1, calls.get());
    }

    @Test
    void saga_unwinds_registered_compensations_on_failure() throws Exception {
        List<String> compensated = new ArrayList<>();
        AgentSessionState state = new AgentSessionState();
        state.registerCompensation("step1", s -> compensated.add("step1"));

        ToolMethod failing = new ToolMethod() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name("failing").description("").build();
            }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
                throw new RuntimeException("boom");
            }
        };

        var executor = new ToolExecutor(new IdempotencyStore(), new OTelAgentTracer(), new AgentEventPublisher());
        ToolResult result = executor.execute(failing, "{}", state);

        assertTrue(result.text().contains("Tool Execution Error"));
        assertTrue(state.isSagaFailed());
        assertTrue(compensated.contains("step1"));
    }

    @Test
    void non_failing_tool_preserves_saga_log() throws Exception {
        AgentSessionState state = new AgentSessionState();
        state.registerCompensation("step1", s -> { throw new IllegalStateException("should-not-run"); });

        ToolMethod ok = new ToolMethod() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name("ok").description("").build();
            }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
                return ToolResult.success("fine");
            }
        };

        var executor = new ToolExecutor(new IdempotencyStore(), new OTelAgentTracer(), new AgentEventPublisher());
        assertEquals("fine", executor.execute(ok, "{}", state).text());
        assertFalse(state.isSagaFailed());
    }
}