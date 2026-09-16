package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.DynamicSubAgentTool;
import de.augmentia.quad.core.tool.SubAgentTool;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link Agent#setCachePrefix(String)} propagates the prefix
 * to registered static and dynamic sub-agent tools (§6B, agent-scoped prefix
 * chain).
 */
class AgentCachePrefixPropagationTest {

    static class SimpleAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    private static QuadToolRegistry registry() {
        return new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));
    }

    private static SubAgentTool subTool(String name) {
        SimpleAgent sub = new SimpleAgent();
        return new SubAgentTool(sub, name);
    }

    private static DynamicSubAgentTool dynamicTool() {
        return new DynamicSubAgentTool(mock(ChatModel.class), registry());
    }

    @Test
    void staticSubAgentInheritsParentPrefix() {
        Agent parent = new SimpleAgent();
        SubAgentTool tool = subTool("research");
        QuadToolRegistry reg = registry();
        reg.register("research", tool);
        parent.setToolRegistry(reg);

        parent.setCachePrefix("block-a");

        assertEquals("block-a", tool.getSubAgent().getCachePrefix());
    }

    @Test
    void staticSubAgentWithExplicitPrefixIsNotOverwritten() {
        Agent parent = new SimpleAgent();
        SubAgentTool tool = subTool("research");
        tool.getSubAgent().setCachePrefix("own-prefix");
        QuadToolRegistry reg = registry();
        reg.register("research", tool);
        parent.setToolRegistry(reg);

        parent.setCachePrefix("block-a");

        assertEquals("own-prefix", tool.getSubAgent().getCachePrefix());
    }

    @Test
    void dynamicSubAgentInheritsByDefault() {
        Agent parent = new SimpleAgent();
        DynamicSubAgentTool tool = dynamicTool();
        QuadToolRegistry reg = registry();
        reg.register("execute_step", tool);
        parent.setToolRegistry(reg);

        parent.setCachePrefix("block-a");

        assertEquals("block-a", tool.getCachePrefix());
    }

    @Test
    void dynamicSubAgentWithOptOutDoesNotInherit() {
        Agent parent = new SimpleAgent();
        DynamicSubAgentTool tool = dynamicTool();
        tool.setInheritCachePrefix(false);
        QuadToolRegistry reg = registry();
        reg.register("execute_step", tool);
        parent.setToolRegistry(reg);

        parent.setCachePrefix("block-a");

        assertNull(tool.getCachePrefix());
    }

    @Test
    void propagationIsSkippedWhenPrefixIsNull() {
        Agent parent = new SimpleAgent();
        SubAgentTool tool = subTool("research");
        QuadToolRegistry reg = registry();
        reg.register("research", tool);
        parent.setToolRegistry(reg);

        parent.setCachePrefix(null);

        assertNull(tool.getSubAgent().getCachePrefix());
    }

    @Test
    void propagationIsSkippedWhenPrefixIsBlank() {
        Agent parent = new SimpleAgent();
        DynamicSubAgentTool tool = dynamicTool();
        QuadToolRegistry reg = registry();
        reg.register("execute_step", tool);
        parent.setToolRegistry(reg);

        parent.setCachePrefix("   ");

        assertNull(tool.getCachePrefix());
    }
}