package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.session.CwdManager;
import de.augmentia.quad.core.hook.pipeline.HookRegistry;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RunSnapshotTest {

    @Test
    void recordCreation() {
        ToolMethod tool = new ToolMethod() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name("t").description("").build();
            }
            @Override
            public ToolResult execute(String json, AgentSessionState s) {
                return ToolResult.success("ok");
            }
        };
        HookRegistry hooks = new HookRegistry();
        CwdManager cwd = new CwdManager();

        RunSnapshot snapshot = new RunSnapshot(
            "system prompt", List.of(tool), hooks, 10, cwd
        );

        assertEquals("system prompt", snapshot.systemPrompt());
        assertEquals(1, snapshot.toolMethods().size());
        assertEquals(hooks, snapshot.hookRegistry());
        assertEquals(10, snapshot.maxToolIterations());
        assertEquals(cwd, snapshot.cwdManager());
    }

    @Test
    void emptyToolList() {
        RunSnapshot snapshot = new RunSnapshot(
            "prompt", List.of(), new HookRegistry(), 0, new CwdManager()
        );
        assertTrue(snapshot.toolMethods().isEmpty());
        assertEquals(0, snapshot.maxToolIterations());
    }
}
