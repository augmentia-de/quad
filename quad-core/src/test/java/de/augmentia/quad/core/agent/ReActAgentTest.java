package de.augmentia.quad.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.agent.runtime.IdempotencyStore;
import de.augmentia.quad.core.agent.runtime.OTelAgentTracer;
import de.augmentia.quad.core.agent.runtime.ToolExecutor;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReActAgentTest {

    static class TestAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    private ToolExecutor directToolExecutor() {
        return new ToolExecutor(new IdempotencyStore(), new OTelAgentTracer(), new AgentEventPublisher()) {
            @Override
            public ToolResult execute(ToolMethod tool, String jsonArguments, AgentSessionState state) {
                try {
                    return tool.execute(jsonArguments, state);
                } catch (Exception e) {
                    return ToolResult.error("Error: " + e.getMessage());
                }
            }
        };
    }

    private TestAgent agentWithTool(ChatModel model, String toolName) {
        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        QuadToolRegistry registry = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));
        registry.register(toolName, new ToolMethod() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name(toolName).build();
            }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
                return ToolResult.success("TOOL_OUTPUT");
            }
        });
        agent.setToolRegistry(registry);
        agent.setToolExecutor(directToolExecutor());
        return agent;
    }

    @Test
    void shouldRunReActLoopWithToolThenReturnFinalText() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("r1").name("shout").arguments("{}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(List.of(req))).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("final")).build());

        TestAgent agent = agentWithTool(model, "shout");

        String result = agent.executeReAct("task", new AgentSessionState());

        assertThat(result).isEqualTo("final");
    }

    @Test
    void shouldStopReActAtMaxIterations() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("r2").name("shout").arguments("{}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(List.of(req))).build());

        TestAgent agent = agentWithTool(model, "shout");
        agent.setReActMaxIterations(2);

        String result = agent.executeReAct("task", new AgentSessionState());

        assertThat(result.substring(0,1)).isEqualTo("[");
    }

    @Test
    void shouldRejectInvalidMaxIterations() {
        TestAgent agent = new TestAgent();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> agent.setReActMaxIterations(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
