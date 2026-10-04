package de.augmentia.quad.core.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ThreadLocal-Lebenszyklus von {@link CurrentSession} (TODO2 A4, A7).
 *
 * <p>Core point: the class is a plain {@code ThreadLocal} without {@code clear()}. A set
 * value stays attached to every reused thread — with pooled HTTP threads and
 * virtual threads that is the basis for session bleed-over between two agents.
 */
class CurrentSessionLifecycleTest {

    @AfterEach
    void tearDown() {
        // Without clear() the last value stays here; that is exactly the point.
        CurrentSession.setCurrent(null);
    }

    @Test
    void regression_setAndGet_roundTripsTheSameInstance() {
        var state = AgentSessionState.create("s-1");
        CurrentSession.setCurrent(state);
        assertThat(CurrentSession.getCurrent()).isSameAs(state);
    }

    @Test
    void defect_A4_noClearMethod_exists() {
        // TODO2 A4: CurrentSession hat bind()/setCurrent(), aber kein clear().
        assertThatThrownBy(() -> {
            try {
                CurrentSession.class.getMethod("clear");
            } catch (NoSuchMethodException e) {
                throw new AssertionError("CurrentSession.clear() is missing — TODO2 A4 is open", e);
            }
        })
                .as("TODO2 A4: CurrentSession needs a clear() for try/finally cleanup")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void defect_A4_valueSurvivesOnReusedThread() throws Exception {
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = AgentSessionState.create("agent-A");
            var second = AgentSessionState.create("agent-B");

            CountDownLatch done = new CountDownLatch(1);
            Object[] seen = new Object[1];

            pool.submit(() -> {
                CurrentSession.setCurrent(first);
                return null;
            }).get(5, TimeUnit.SECONDS);

            // A second "agent" on the same thread: the ThreadLocal is still attached.
            pool.submit(() -> {
                CurrentSession.setCurrent(second);
                seen[0] = CurrentSession.getCurrent();
                done.countDown();
            }).get(5, TimeUnit.SECONDS);

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seen[0]).isSameAs(second);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void defect_A4_bindingToNull_leavesTheKeyPresentInTheThreadLocalMap() throws Exception {
        // That is the state that TaskExecutor:220 in kafka-platform leaves behind.
        CurrentSession.setCurrent(AgentSessionState.create("x"));
        CurrentSession.setCurrent(null);

        assertThat(CurrentSession.getCurrent())
                .as("setCurrent(null) empties the value but does not remove the key")
                .isNull();
    }
}