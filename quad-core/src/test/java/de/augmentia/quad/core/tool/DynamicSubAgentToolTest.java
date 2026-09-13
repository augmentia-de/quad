package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.when;

class DynamicSubAgentToolTest {

    private QuadToolRegistry buildRegistry() {
        QuadToolRegistry registry = new QuadToolRegistry(new ToolArgsMapper(new ObjectMapper()));

        registry.register("echo", new ToolMethod() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name("echo").description("Echoes input").build();
            }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
                state.addFinding("echo executed");
                return ToolResult.success("echo: " + jsonArguments);
            }
        });

        registry.register("noop", new ToolMethod() {
            @Override
            public ToolSpecification spec() {
                return ToolSpecification.builder().name("noop").description("No operation").build();
            }

            @Override
            public ToolResult execute(String jsonArguments, AgentSessionState state) {
                return ToolResult.success("noop ok");
            }
        });

        return registry;
    }

    @Test
    void shouldCreateSubAgentWithSelectedTools() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("done")).build());

        DynamicSubAgentTool tool = new DynamicSubAgentTool(model, buildRegistry());
        AgentSessionState state = new AgentSessionState();
        ToolResult result = tool.execute(
            "{\"prompt\": \"do something\", \"tools\": [\"echo\"]}", state);

        assertThat(result.text()).isEqualTo("done");
    }

    @Test
    void shouldUseAllToolsWhenToolsListIsEmpty() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("all tools")).build());

        DynamicSubAgentTool tool = new DynamicSubAgentTool(model, buildRegistry());
        AgentSessionState state = new AgentSessionState();
        ToolResult result = tool.execute("{\"prompt\": \"use all tools\"}", state);

        assertThat(result.text()).isEqualTo("all tools");
    }

    @Test
    void shouldUseAllToolsWhenToolsOmitted() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("no tools param")).build());

        DynamicSubAgentTool tool = new DynamicSubAgentTool(model, buildRegistry());
        AgentSessionState state = new AgentSessionState();
        ToolResult result = tool.execute("{\"prompt\": \"test\"}", state);

        assertThat(result.text()).isEqualTo("no tools param");
    }

    @Test
    void shouldMergeFindingsFromSubAgent() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("result")).build());

        DynamicSubAgentTool tool = new DynamicSubAgentTool(model, buildRegistry());
        AgentSessionState parentState = new AgentSessionState();
        parentState.addFinding("parent finding");

        tool.execute("{\"prompt\": \"test\", \"tools\": [\"echo\"]}", parentState);

        assertThat(parentState.findings()).contains("parent finding");
    }

    @Test
    void shouldReturnErrorOnLLMFailure() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM error"));

        DynamicSubAgentTool tool = new DynamicSubAgentTool(model, buildRegistry());
        AgentSessionState state = new AgentSessionState();
        ToolResult result = tool.execute("{\"prompt\": \"boom\"}", state);

        assertThat(result.text()).contains("Error in dynamic sub-agent");
        assertThat(result.text()).contains("LLM error");
    }

    @Test
    void shouldExposeSpecWithCorrectParameters() {
        DynamicSubAgentTool tool = new DynamicSubAgentTool(mock(ChatModel.class), buildRegistry());
        ToolSpecification spec = tool.spec();

        assertThat(spec.name()).isEqualTo("execute_step");
        assertThat(spec.description()).isNotEmpty();
        assertThat(spec.parameters()).isNotNull();
    }

    @Test
    void shouldThrowOnNullChatModel() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new DynamicSubAgentTool(null, buildRegistry()));
    }

    @Test
    void shouldThrowOnNullRegistry() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new DynamicSubAgentTool(mock(ChatModel.class), null));
    }

    @Test
    void shouldPassParentStateDirectlyToSubAgent() throws Exception {
        AtomicReference<AgentSessionState> seenState = new AtomicReference<>();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            seenState.set(CurrentSession.getCurrent());
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        });

        DynamicSubAgentTool tool = new DynamicSubAgentTool(model, buildRegistry());
        AgentSessionState parentState = new AgentSessionState();
        parentState.addFinding("parent only");

        tool.execute("{\"prompt\": \"check state\"}", parentState);

        assertThat(seenState.get()).isSameAs(parentState);
        assertThat(seenState.get().findings()).contains("parent only");
    }

    @Test
    void shouldThrowOnInvalidMaxDepth() {
        ChatModel model = mock(ChatModel.class);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new DynamicSubAgentTool(model, buildRegistry(), 0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new DynamicSubAgentTool(model, buildRegistry(), -1));
    }
}
