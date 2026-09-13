package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.agent.channels.ChannelManager;
import de.augmentia.quad.core.agent.channels.QueueChannel;
import de.augmentia.quad.core.capability.context.ContextManager;
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
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentIntegrationTest {

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
    void shouldRenderContextManagerBlocksIntoSystemMessage() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        ContextManager ctx = new ContextManager();
        ctx.set("task", "Analyse X");
        ctx.addBlock(new ContextManager.ProtectedBlock("rules", "Be concise."));
        agent.setContextManager(ctx);

        AgentSessionState state = new AgentSessionState();
        String result = agent.run("Do it", state);

        assertThat(result).isEqualTo("ok");
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        ChatMessage first = captor.getValue().messages().get(0);
        assertThat(first).isInstanceOf(SystemMessage.class);
        String content = ((SystemMessage) first).text();
        assertThat(content).contains("Prompt: Do it");
        assertThat(content).contains("[task]: Analyse X");
        assertThat(content).contains("[PROTECTED:rules]: Be concise.");
    }

    @Test
    void shouldInjectChannelNotificationsBeforeModelCall() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        QueueChannel channel = new QueueChannel();
        channel.put("Disk full on /tmp");
        ChannelManager channelManager = new ChannelManager();
        channelManager.addChannel(channel);
        agent.setChannelManager(channelManager);

        AgentSessionState state = new AgentSessionState();
        agent.run("Do it", state);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        assertThat(captor.getValue().messages()).anyMatch(m ->
                m instanceof SystemMessage sm && sm.text().contains("[ASYNC NOTIFICATION] Disk full on /tmp"));
    }

    @Test
    void shouldExecuteCodeActWithToolsThenReturnFinalText() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("c1").name("shout").arguments("{}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(List.of(req))).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("final")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        QuadToolRegistry registry = new QuadToolRegistry(new ToolArgsMapper(
                new com.fasterxml.jackson.databind.ObjectMapper()));
        registry.register("shout", new ToolMethod() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name("shout").build();
            }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
                return ToolResult.success("LOUD");
            }
        });
        agent.setToolRegistry(registry);
        agent.setToolExecutor(directToolExecutor());

        String result = agent.run("task", new AgentSessionState());

        assertThat(result).isEqualTo("final");
    }

    @Test
    void shouldReturnResultOfFinalToolInGuardedCodeAct() {
        ChatModel model = mock(ChatModel.class);
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("c2").name("return_result").arguments("{\"answer\":\"42\"}").build();
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(List.of(req))).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("FINAL:42")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        QuadToolRegistry registry = new QuadToolRegistry(new ToolArgsMapper(
                new com.fasterxml.jackson.databind.ObjectMapper()));
        registry.register("return_result", new ToolMethod() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name("return_result").build();
            }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
                return ToolResult.success("FINAL:42");
            }
        });
        agent.setToolRegistry(registry);
        agent.setToolExecutor(directToolExecutor());

        String result = agent.run("task", new AgentSessionState());

        assertThat(result).isEqualTo("FINAL:42");
    }

    @Test
    void shouldReturnPlainTextAsFinalAnswer() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("final answer")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);

        String result = agent.run("task", new AgentSessionState());
        assertThat(result).isEqualTo("final answer");
    }
}
