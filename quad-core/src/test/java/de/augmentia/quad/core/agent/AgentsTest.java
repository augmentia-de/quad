package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.session.AgentSessionState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentsTest {

    @Test
    void fromConfigBuildsAgent() {
        Agent agent = Agents.fromConfig(AgentConfig.builder().systemPrompt("x").build());

        assertNotNull(agent);
        assertEquals("x", agent.getSystemPrompt());
        assertNotNull(agent.getToolRegistry());
    }

    @Test
    void builderReturnsWiredAgent() {
        Agent agent = Agents.builder(TestAgent.class).build();

        assertNotNull(agent);
        assertNotNull(agent.getToolRegistry());
        assertNotNull(agent.getEventPublisher());
    }

    static class TestAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }
}
