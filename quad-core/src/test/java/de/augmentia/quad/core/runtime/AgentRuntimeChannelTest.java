package de.augmentia.quad.core.runtime;

import de.augmentia.quad.core.agent.channels.ChannelManager;
import de.augmentia.quad.core.agent.channels.QueueChannel;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.agent.runtime.AgentRuntime;
import de.augmentia.quad.core.agent.runtime.OTelAgentTracer;
import de.augmentia.quad.core.hook.pipeline.HookRegistry;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolArgsMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeChannelTest {

    @Test
    void shouldInjectChannelNotificationsIntoReActRequest() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("answer")).build());

        QueueChannel channel = new QueueChannel();
        channel.put("heartbeat");
        ChannelManager channelManager = new ChannelManager();
        channelManager.addChannel(channel);

        AgentRuntime runtime = new AgentRuntime(
                model,
                new QuadToolRegistry(new ToolArgsMapper(new com.fasterxml.jackson.databind.ObjectMapper())),
                null, 5,
                new HookRegistry(), new AgentEventPublisher(), new OTelAgentTracer(), null,
                channelManager);

        AgentSessionState state = AgentSessionState.create("s-1");
        String result = runtime.run(null, "task", state);

        assertThat(result).isEqualTo("answer");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        assertThat(captor.getValue().messages()).anyMatch(m ->
                m instanceof SystemMessage sm && sm.text().contains("[ASYNC NOTIFICATION] heartbeat"));
    }
}
