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
 * <p>Kernpunkt: die Klasse ist ein reines {@code ThreadLocal} ohne {@code clear()}. Ein gesetzter
 * Wert bleibt auf jedem wiederverwendeten Thread hängen — bei gepoolten HTTP-Threads und
 * virtual Threads ist das die Grundlage für Session-Bleed-over zwischen zwei Agenten.
 */
class CurrentSessionLifecycleTest {

    @AfterEach
    void tearDown() {
        // Ohne clear() bleibt hier der letzte Wert stehen; das ist genau der Punkt.
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
                throw new AssertionError("CurrentSession.clear() fehlt — TODO2 A4 ist offen", e);
            }
        })
                .as("TODO2 A4: CurrentSession braucht ein clear() fuer try/finally-Cleanup")
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

            // Zweiter "Agent" auf demselben Thread: der ThreadLocal haengt noch.
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
        // Das ist der Zustand, den TaskExecutor:220 in kafka-platform hinterlaesst.
        CurrentSession.setCurrent(AgentSessionState.create("x"));
        CurrentSession.setCurrent(null);

        assertThat(CurrentSession.getCurrent())
                .as("setCurrent(null) leert den Wert, entfernt den Key aber nicht")
                .isNull();
    }
}