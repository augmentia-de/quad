package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.session.AgentSessionState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentBuilderTest {

    static class TestAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    @Test
    void shouldCreateAgent() {
        Agent agent = AgentBuilder.create(TestAgent.class)
            .build();

        assertNotNull(agent);
        assertNotNull(agent.getToolRegistry());
        assertNotNull(agent.getEventPublisher());
        assertNotNull(agent.getHookRegistry());
    }

    @Test
    void shouldSetToolRegistry() {
        Agent agent = AgentBuilder.create(TestAgent.class)
            .build();

        assertNotNull(agent.getToolRegistry());
    }

    @Test
    void shouldSetWorkspace() {
        Path workspace = Path.of("/custom/workspace");
        Agent agent = AgentBuilder.create(TestAgent.class)
            .withWorkspace(workspace)
            .build();

        assertNotNull(agent);
    }

    @Test
    void shouldCreateDevProfileAgent() {
        Agent agent = AgentBuilder.create(TestAgent.class)
            .withDevProfile()
            .build();

        assertNotNull(agent);
    }

    @Test
    void shouldCreateProdProfileAgent() {
        Agent agent = AgentBuilder.create(TestAgent.class)
            .withProdProfile()
            .build();

        assertNotNull(agent);
    }

    @Test
    void shouldApplyDefaults() {
        Agent agent = AgentBuilder.create(TestAgent.class)
            .withDefaults()
            .build();

        assertNotNull(agent.getHookRegistry());
    }

    @Test
    void shouldFilterToolsByNames() {
        Set<String> tools = Set.of("readFile", "writeFile");
        Agent agent = AgentBuilder.create(TestAgent.class)
            .withTools(tools)
            .build();

        assertNotNull(agent.getToolRegistry());
    }
}