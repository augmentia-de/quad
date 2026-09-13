package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.agent.runtime.IdempotencyStore;
import de.augmentia.quad.core.agent.runtime.OTelAgentTracer;
import de.augmentia.quad.core.agent.runtime.ToolExecutor;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.ToolMethod;
import de.augmentia.quad.core.tool.ToolResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentStructuredOutputTest {

    static class TestAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    record WeatherReport(String city, double temperature, String condition) {}

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
    void executeStructuredReturnsStructuredOutput() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from(
                "{\"city\": \"Berlin\", \"temperature\": 22.5, \"condition\": \"sunny\"}")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        agent.setStructuredOutputConfig(StructuredOutputConfig.staticModel(WeatherReport.class));

        AgentResult result = agent.executeStructured("What's the weather?", new AgentSessionState());

        assertThat(result.finalAnswer()).contains("Berlin");
        assertThat(result.hasStructuredOutput()).isTrue();
        assertThat(result.structuredOutput()).contains("Berlin");
        assertThat(result.structuredOutput()).contains("22.5");
        assertThat(result.metrics().durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void executeStructuredWithoutConfigReturnsNullStructuredOutput() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("plain text answer")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);

        AgentResult result = agent.executeStructured("task", new AgentSessionState());

        assertThat(result.finalAnswer()).isEqualTo("plain text answer");
        assertThat(result.hasStructuredOutput()).isFalse();
        assertThat(result.structuredOutput()).isNull();
    }

    @Test
    void forceRetryOnInvalidJsonThenSucceeds() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("not valid json at all")).build(),
            ChatResponse.builder().aiMessage(AiMessage.from(
                "{\"city\": \"Munich\", \"temperature\": 18.0, \"condition\": \"cloudy\"}")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        agent.setStructuredOutputConfig(StructuredOutputConfig.staticModel(WeatherReport.class));

        AgentResult result = agent.executeStructured("weather?", new AgentSessionState());

        assertThat(result.structuredOutput()).contains("Munich");
        assertThat(result.hasStructuredOutput()).isTrue();
        // Verify LLM was called twice (first attempt + force retry)
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void forceRetryOnlyAttemptedOnce() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("still bad json")).build(),
            ChatResponse.builder().aiMessage(AiMessage.from("also bad")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        agent.setStructuredOutputConfig(StructuredOutputConfig.dynamicSchema(
            "{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\"}}}"));

        AgentResult result = agent.executeStructured("task", new AgentSessionState());

        // After force retry fails, we get the text as-is
        assertThat(result.finalAnswer()).isEqualTo("also bad");
        assertThat(result.hasStructuredOutput()).isFalse();
        // Only 2 calls: initial + force retry
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void responseFormatInjectedIntoChatRequest() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("{\"x\": \"y\"}")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        agent.setStructuredOutputConfig(StructuredOutputConfig.dynamicSchema(
            "{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\"}}}"));

        agent.executeStructured("task", new AgentSessionState());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        var request = captor.getValue();
        assertThat(request.responseFormat()).isNotNull();
        assertThat(request.responseFormat().type()).isEqualTo(ResponseFormatType.JSON);
        assertThat(request.responseFormat().jsonSchema()).isNotNull();
    }

    @Test
    void noResponseFormatWhenConfigDisabled() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("answer")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        // No structuredOutputConfig set

        agent.execute("task", new AgentSessionState());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        assertThat(captor.getValue().responseFormat()).isNull();
    }

    @Test
    void executePathEnforcesStructuredOutput() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("{\"x\": \"y\"}")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        agent.setStructuredOutputConfig(StructuredOutputConfig.dynamicSchema(
            "{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\"}}}"));

        // execute() runs via run(prompt, state) (used by workflow + playground) — must enforce structured output too
        agent.execute("task", new AgentSessionState());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        var request = captor.getValue();
        assertThat(request.responseFormat()).isNotNull();
        assertThat(request.responseFormat().type()).isEqualTo(ResponseFormatType.JSON);
        assertThat(request.responseFormat().jsonSchema()).isNotNull();
    }

    @Test
    void systemMessageDoesNotEmbedSchemaWhenNotInJsonMode() {
        // Structured output is enforced via `response_format` only; the schema must NOT be
        // duplicated into the system prompt.
        String schema = "{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\"}}}";
        TestAgent agent = new TestAgent();
        agent.setLlm(mock(ChatModel.class));
        agent.setStructuredOutputConfig(StructuredOutputConfig.dynamicSchema(schema));

        List<ChatMessage> messages = agent.initialMessages("task", new AgentSessionState());

        List<SystemMessage> system = messages.stream()
            .filter(m -> m instanceof SystemMessage)
            .map(m -> (SystemMessage) m)
            .toList();
        assertThat(system).isNotEmpty();
        assertThat(system.get(0).text()).doesNotContain("## JSON Output");
        assertThat(system.get(0).text()).doesNotContain(schema);
    }

    @Test
    void jsonInputModeAlwaysProducesUserMessage() {
        String schema = "{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\"}}}";
        TestAgent agent = new TestAgent();
        agent.setLlm(mock(ChatModel.class));
        agent.setJsonInput(true);
        agent.setStructuredOutputConfig(StructuredOutputConfig.dynamicSchema(schema));

        List<ChatMessage> messages = agent.initialMessages("task", new AgentSessionState());

        List<SystemMessage> system = messages.stream()
            .filter(m -> m instanceof SystemMessage)
            .map(m -> (SystemMessage) m)
            .toList();
        assertThat(system).isNotEmpty();
        // schema must not be duplicated into the system prompt
        assertThat(system.get(0).text()).doesNotContain("## JSON Output");
        assertThat(system.get(0).text()).doesNotContain(schema);

        boolean hasUser = messages.stream()
            .anyMatch(m -> m instanceof dev.langchain4j.data.message.UserMessage);
        assertThat(hasUser).isTrue();
    }

    @Test
    void systemAndUserMessagePathKeepsSchemaOutOfSystemMessage() throws Exception {
        String schema = "{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\"}}}";
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
            ChatResponse.builder().aiMessage(AiMessage.from("{\"x\": \"y\"}")).build());

        TestAgent agent = new TestAgent();
        agent.setLlm(model);
        agent.setStructuredOutputConfig(StructuredOutputConfig.dynamicSchema(schema));

        // executeStructured(system, user, state) builds the system message directly (runWithMessages)
        AgentResult result = agent.executeStructured("System message", "user", new AgentSessionState());

        assertThat(result.finalAnswer()).contains("y");
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        var request = captor.getValue();
        assertThat(request.messages())
            .filteredOn(m -> m instanceof SystemMessage)
            .anyMatch(m -> !((SystemMessage) m).text().contains("## JSON Output"));
        // response_format still enforces the schema
        assertThat(request.responseFormat()).isNotNull();
        assertThat(request.responseFormat().type()).isEqualTo(ResponseFormatType.JSON);
        assertThat(request.responseFormat().jsonSchema()).isNotNull();
    }

    @Test
    void builderWithStructuredOutputClass() {
        TestAgent agent = AgentBuilder.create(TestAgent.class)
            .withStructuredOutput(WeatherReport.class)
            .build();

        assertThat(agent.getStructuredOutputConfig()).isNotNull();
        assertThat(agent.getStructuredOutputConfig().isEnabled()).isTrue();
        assertFalse(agent.getStructuredOutputConfig().dynamicSchema());
        assertEquals(WeatherReport.class, agent.getStructuredOutputConfig().outputClass());
    }

    @Test
    void builderWithStructuredOutputSchema() {
        String schema = "{\"type\": \"object\", \"properties\": {\"x\": {\"type\": \"string\"}}}";
        TestAgent agent = AgentBuilder.create(TestAgent.class)
            .withStructuredOutputSchema(schema)
            .build();

        assertThat(agent.getStructuredOutputConfig()).isNotNull();
        assertTrue(agent.getStructuredOutputConfig().dynamicSchema());
        assertEquals(schema, agent.getStructuredOutputConfig().jsonSchema());
    }

    @Test
    void builderWithStructuredOutputConfig() {
        var config = StructuredOutputConfig.staticModel(WeatherReport.class, "Custom force!");
        TestAgent agent = AgentBuilder.create(TestAgent.class)
            .withStructuredOutputConfig(config)
            .build();

        assertThat(agent.getStructuredOutputConfig()).isEqualTo(config);
    }

    private static void assertFalse(boolean value) { org.junit.jupiter.api.Assertions.assertFalse(value); }
    private static void assertTrue(boolean value) { org.junit.jupiter.api.Assertions.assertTrue(value); }
    private static void assertEquals(Object expected, Object actual) { org.junit.jupiter.api.Assertions.assertEquals(expected, actual); }
}
