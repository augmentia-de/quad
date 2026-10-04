package de.augmentia.quad.core.agent.runtime;

import de.augmentia.quad.core.annotation.Idempotent;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.ReflectiveToolMethod;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import de.augmentia.quad.core.tool.ToolMeta;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTest {

    /**
     * A hand-written {@link ToolMethod} may still declare its metadata directly. The default
     * {@link ToolMethod#meta()} derives a non-idempotent meta, so this variant overrides it —
     * that is the intended way to express idempotency without an annotation.
     *
     * <p>This test used to put {@code @Idempotent} on {@code spec()} and thereby pinned the
     * defect described in TODO2 A6: the executor reflected on the {@code spec()} method of the
     * implementation class instead of reading metadata. It is kept as a plain hand-written-tool
     * test now; the reflective path is covered by
     * {@code idempotent_annotationOnToolMethod_shouldActivateTheCache}.
     */
    @Test
    void handWrittenToolWithIdempotentMeta_replaysCachedResult() throws Exception {
        var calls = new AtomicInteger();
        ToolMethod idem = new ToolMethod() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name("idempotent").description("").build();
            }

            @Override
            public ToolMeta meta() {
                return ToolMeta.simple(spec().name(), spec().description()).withIdempotent(true);
            }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
                calls.incrementAndGet();
                return ToolResult.success("result-" + calls.get());
            }
        };

        AgentSessionState state = new AgentSessionState();
        var executor = new ToolExecutor(new IdempotencyStore(), new OTelAgentTracer(), new AgentEventPublisher());

        ToolResult first = executor.execute(idem, "{}", state);
        ToolResult second = executor.execute(idem, "{}", state);

        assertEquals("result-1", first.text());
        assertEquals("result-1", second.text()); // cached, not re-run
        assertEquals(1, calls.get());
    }

    /** Tool-Methode mit {@code @Idempotent} an der annotierten Methode, wie es gedacht ist. */
    public static class IdempotentToolBean {
        static final AtomicInteger CALLS = new AtomicInteger();

        @de.augmentia.quad.core.annotation.Tool("idempotent tool")
        @Idempotent
        public String remember(@de.augmentia.quad.core.annotation.Param("key") String key) {
            CALLS.incrementAndGet();
            return "value-for-" + key;
        }
    }

    public static class PlainToolBean {
        static final AtomicInteger CALLS = new AtomicInteger();

        @de.augmentia.quad.core.annotation.Tool("plain tool")
        public String act(@de.augmentia.quad.core.annotation.Param("key") String key) {
            CALLS.incrementAndGet();
            return "acted-" + key;
        }
    }

    @Test
    void idempotent_annotationOnToolMethod_shouldActivateTheCache() throws Exception {
        IdempotentToolBean.CALLS.set(0);
        var bean = new IdempotentToolBean();
        var tm = new ReflectiveToolMethod(
                IdempotentToolBean.class.getMethod("remember", String.class),
                bean, new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper()));

        var state = new AgentSessionState();
        var executor = new ToolExecutor(new IdempotencyStore(), new OTelAgentTracer(), new AgentEventPublisher());

        ToolResult first = executor.execute(tm, "{\"key\":\"k1\"}", state);
        ToolResult second = executor.execute(tm, "{\"key\":\"k1\"}", state);

        assertEquals(first.text(), second.text());
        assertEquals(1, IdempotentToolBean.CALLS.get(),
                "@Idempotent on the tool method must activate the cache (TODO2 A6 fixed: "
                        + "die Annotation wird jetzt in ToolMeta materialisiert)");
    }

    @Test
    void metaCarriesIdempotencyFromTheToolMethodAnnotation() throws Exception {
        var tm = new ReflectiveToolMethod(
                IdempotentToolBean.class.getMethod("remember", String.class),
                new IdempotentToolBean(),
                new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper()));

        assertTrue(tm.meta().idempotent());
    }

    @Test
    void metaCarriesDescriptionFromTheToolAnnotation() throws Exception {
        var tm = new ReflectiveToolMethod(
                PlainToolBean.class.getMethod("act", String.class),
                new PlainToolBean(),
                new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper()));

        assertEquals("plain tool", tm.meta().description());
        assertFalse(tm.meta().idempotent());
    }

    @Test
    void regression_nonIdempotentToolAlwaysExecutes() throws Exception {
        PlainToolBean.CALLS.set(0);
        var bean = new PlainToolBean();
        var tm = new ReflectiveToolMethod(
                PlainToolBean.class.getMethod("act", String.class),
                bean, new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper()));

        var state = new AgentSessionState();
        var executor = new ToolExecutor(new IdempotencyStore(), new OTelAgentTracer(), new AgentEventPublisher());

        executor.execute(tm, "{\"key\":\"k2\"}", state);
        executor.execute(tm, "{\"key\":\"k2\"}", state);

        assertEquals(2, PlainToolBean.CALLS.get(),
                "ohne @Idempotent wird jedes Mal ausgefuehrt");
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