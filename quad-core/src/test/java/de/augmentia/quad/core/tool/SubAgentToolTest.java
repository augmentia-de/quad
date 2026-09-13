package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.agent.runtime.IdempotencyStore;
import de.augmentia.quad.core.agent.runtime.OTelAgentTracer;
import de.augmentia.quad.core.agent.runtime.ToolExecutor;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubAgentToolTest {

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

    @Test
    void shouldDelegateToSubAgentWithSharedState() {
        AtomicReference<AgentSessionState> seen = new AtomicReference<>();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            seen.set(CurrentSession.getCurrent());
            return ChatResponse.builder().aiMessage(AiMessage.from("sub result")).build();
        });

        TestAgent subAgent = new TestAgent();
        subAgent.setLlm(model);
        subAgent.setToolExecutor(directToolExecutor());

        SubAgentTool tool = new SubAgentTool(subAgent, "research", "Run the research sub-agent");

        AgentSessionState state = new AgentSessionState();
        ToolResult result = tool.execute("{\"prompt\":\"analyse X\"}", state);

        assertThat(result.text()).isEqualTo("sub result");
        assertThat(seen.get()).isSameAs(state);
        verify(model).chat(any(ChatRequest.class));
    }

    @Test
    void shouldExposeSpecWithRequiredPrompt() {
        SubAgentTool tool = new SubAgentTool(new TestAgent(), "research", "Run research");

        ToolSpecification spec = tool.spec();

        assertThat(spec.name()).isEqualTo("research");
        assertThat(spec.description()).isEqualTo("Run research");
        assertThat(spec.parameters()).isNotNull();
    }

    @Test
    void shouldStopRecursionAtMaxDepth() {
        class RecursiveAgent extends TestAgent {
            private SubAgentTool tool;

            void setTool(SubAgentTool tool) {
                this.tool = tool;
            }

            @Override
            public String executeReAct(String prompt, AgentSessionState state) {
                return tool.execute("{\"prompt\":\"again\"}", state).text();
            }
        }

        RecursiveAgent agent = new RecursiveAgent();
        SubAgentTool tool = new SubAgentTool(agent, "loop", "loop", 2);
        agent.setTool(tool);

        ToolResult result = tool.execute("{\"prompt\":\"start\"}", new AgentSessionState());

        assertThat(result.text()).isEqualTo("[ERROR] Error in sub-agent: maximum recursion depth of 2 reached.");
    }

    @Test
    void shouldReturnErrorOnSubAgentFailure() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("llm down"));

        TestAgent subAgent = new TestAgent();
        subAgent.setLlm(model);
        subAgent.setToolExecutor(directToolExecutor());

        SubAgentTool tool = new SubAgentTool(subAgent, "research", "Run research");

        ToolResult result = tool.execute("{\"prompt\":\"boom\"}", new AgentSessionState());

        assertThat(result.text()).isEqualTo("[ERROR] Error in sub-agent: llm down");
    }
}
