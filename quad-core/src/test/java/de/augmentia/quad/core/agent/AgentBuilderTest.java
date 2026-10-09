package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.annotation.Tool;
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

    static class Tools {
        @Tool(description = "ping")
        public String ping(String message) {
            return "pong " + message;
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
    void shouldBuildDefaultAgent() {
        Agent agent = AgentBuilder.defaultAgent()
            .build();

        assertNotNull(agent);
        assertNotNull(agent.getToolRegistry());
        assertNotNull(agent.getEventPublisher());
        assertNotNull(agent.getHookRegistry());
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

    @Test
    void shouldBuildFromExistingInstance() {
        TestAgent instance = new TestAgent();
        Agent agent = AgentBuilder.of(instance).build();

        assertSame(instance, agent);
        assertNotNull(agent.getToolRegistry());
    }

    @Test
    void shouldApplyAgentConfig() {
        AgentConfig cfg = AgentConfig.builder()
            .systemPrompt("sys")
            .maxReActIterations(3)
            .contextMaxTokens(500)
            .build();

        Agent agent = AgentBuilder.create(TestAgent.class)
            .withConfig(cfg)
            .build();

        assertEquals("sys", agent.getSystemPrompt());
        assertEquals(500, agent.getContextMaxTokens());
    }

    @Test
    void shouldRegisterToolObjects() {
        Agent agent = AgentBuilder.defaultAgent()
            .withToolObjects(new Tools())
            .build();

        assertNotNull(agent.getToolRegistry().get("ping"));
    }
}