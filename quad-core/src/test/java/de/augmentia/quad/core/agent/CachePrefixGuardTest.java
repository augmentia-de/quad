package de.augmentia.quad.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import de.augmentia.quad.core.tool.ToolMethod;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CachePrefixGuardTest {

    static class TooledAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }

        @Tool("Adds two values")
        public String add(int a, int b) {
            return String.valueOf(a + b);
        }

        @Tool("Listens on an address")
        public String listen(String address) {
            return address;
        }
    }

    static class AltTooledAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }

        @Tool("Adds two values")
        public String add(int a, int b) {
            return String.valueOf(a + b);
        }

        @Tool("Different description for the same call")
        public String listen(String address) {
            return address;
        }
    }

    /** Builds an agent whose registry contains the given tools in the given order. */
    private static Agent agentWithTools(String... names) {
        return agentWithTools(TooledAgent.class, names);
    }

    private static Agent agentWithTools(Class<? extends Agent> agentClass, String... names) {
        QuadToolRegistry seedRegistry = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));
        try {
            seedRegistry.registerFromAgent(agentClass.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Map<String, ToolMethod> byName = new LinkedHashMap<>();
        for (ToolMethod tm : seedRegistry.getAll()) {
            byName.put(tm.spec().name(), tm);
        }

        QuadToolRegistry ordered = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));
        for (String name : names) {
            ordered.register(name, byName.get(name));
        }

        try {
            Agent agent = agentClass.getDeclaredConstructor().newInstance();
            agent.setToolRegistry(ordered);
            return agent;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void identicalSetInSameOrderPasses() {
        Agent a = agentWithTools("add", "listen");
        Agent b = agentWithTools("add", "listen");

        assertEquals(CachePrefixGuard.toolFingerprint(a), CachePrefixGuard.toolFingerprint(b));
        assertDoesNotThrow(() -> CachePrefixGuard.requireIdenticalTools(a, b));
    }

    @Test
    void differentOrderIsDetected() {
        Agent a = agentWithTools("add", "listen");
        Agent b = agentWithTools("listen", "add");

        assertNotEquals(CachePrefixGuard.toolFingerprint(a), CachePrefixGuard.toolFingerprint(b));
        assertThrows(IllegalStateException.class, () -> CachePrefixGuard.requireIdenticalTools(a, b));
    }

    @Test
    void changedDescriptionIsDetected() {
        Agent a = agentWithTools(TooledAgent.class, "add", "listen");
        Agent b = agentWithTools(AltTooledAgent.class, "add", "listen");

        assertNotEquals(CachePrefixGuard.toolFingerprint(a), CachePrefixGuard.toolFingerprint(b));
        assertThrows(IllegalStateException.class, () -> CachePrefixGuard.requireIdenticalTools(a, b));
    }
}