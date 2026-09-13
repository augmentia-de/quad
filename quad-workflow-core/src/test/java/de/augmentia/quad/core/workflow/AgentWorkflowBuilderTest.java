package de.augmentia.quad.core.workflow;
import de.augmentia.quad.core.scope.FieldExtractor;
import de.augmentia.quad.core.scope.AgentScope;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.config.StructuredInputConfig;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentWorkflowBuilderTest {

    static class TestAgent extends Agent {
        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    record TaskAnalysis(String complexity, List<String> recommendedTools, int estimatedHours) {}
    record ImplementationPlan(List<String> steps) {}

    private static String lastText(ChatRequest request) {
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            ChatMessage m = request.messages().get(i);
            if (m instanceof UserMessage u) return u.singleText();
            if (m instanceof dev.langchain4j.data.message.SystemMessage s) return s.text();
        }
        return "";
    }

    @Test
    void workflowPassesTypedDataBetweenAgents() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
            .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(
                "{\"complexity\":\"complex\",\"recommendedTools\":[\"find_files\",\"grep_search\"],\"estimatedHours\":4}")).build())
            .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(
                "{\"steps\":[\"collect\",\"filter\",\"report\"]}")).build());

        TestAgent analyzer = new TestAgent();
        analyzer.setLlm(model);
        TestAgent planner = new TestAgent();
        planner.setLlm(model);

        AgentWorkflowBuilder workflow = new AgentWorkflowBuilder()
            .step("analyze", analyzer,
                StructuredOutputConfig.staticModel(TaskAnalysis.class), "analysis")
            .transform("analysis", "tools_for_planner",
                analysis -> FieldExtractor.extract(analysis, "recommendedTools"))
            .step("plan", planner,
                StructuredInputConfig.fromTemplate("""
                    Create a plan.
                    Task: {{initialPrompt}}
                    Complexity: {{analysis.complexity}}
                    Tools: {{tools_for_planner}}
                    """),
                StructuredOutputConfig.staticModel(ImplementationPlan.class), "plan");

        AgentWorkflowResult result = workflow.execute("Build a REST API");

        assertThat(result.workflowId()).isNotBlank();

        // Typed record stored in scope
        TaskAnalysis analysis = result.scope().get("analysis");
        assertThat(analysis).isInstanceOf(TaskAnalysis.class);
        assertThat(analysis.complexity()).isEqualTo("complex");
        assertThat(analysis.recommendedTools()).containsExactly("find_files", "grep_search");

        // Transformation result available
        @SuppressWarnings("unchecked")
        List<String> tools = result.scope().get("tools_for_planner");
        assertThat(tools).containsExactly("find_files", "grep_search");

        // Typed output of last step
        ImplementationPlan plan = result.scope().get("plan");
        assertThat(plan).isInstanceOf(ImplementationPlan.class);
        assertThat(plan.steps()).containsExactly("collect", "filter", "report");

        // Step 2 got rendered template with flattened fields
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());
        String secondPrompt = lastText(captor.getAllValues().get(1));
        assertThat(secondPrompt).contains("Task: Build a REST API");
        assertThat(secondPrompt).contains("Complexity: complex");
        assertThat(secondPrompt).contains("Tools: [\"find_files\",\"grep_search\"]");

        assertThat(result.finalText()).isEqualTo("{\"steps\":[\"collect\",\"filter\",\"report\"]}");
    }

    @Test
    void defaultInputChainsPreviousOutput() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
            .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("first answer")).build())
            .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("second answer")).build());

        TestAgent first = new TestAgent();
        first.setLlm(model);
        TestAgent second = new TestAgent();
        second.setLlm(model);

        AgentWorkflowResult result = new AgentWorkflowBuilder()
            .step("first", first, null, "first_output")
            .step("second", second, null, "second_output")
            .execute("initial task");

        String firstText = result.scope().get("first_output_text");
        assertThat(firstText).isEqualTo("first answer");
        String secondText = result.scope().get("second_output_text");
        assertThat(secondText).isEqualTo("second answer");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());
        assertThat(lastText(captor.getAllValues().get(0))).contains("initial task");
        assertThat(lastText(captor.getAllValues().get(1))).contains("first answer");
    }

    @Test
    void transformWithDottedPathResolvesField() {
        AgentScope scope = new AgentScope("wf-1");
        scope.put("analysis", new TaskAnalysis("medium", List.of("a", "b"), 2));

        new AgentWorkflowBuilder()
            .withScope(scope)
            .transform("analysis.complexity", "complexity_upper",
                value -> String.valueOf(value).toUpperCase())
            .transform("missing.path", "missing_result", value -> "fallback")
            .execute("task");

        String upper = scope.get("complexity_upper");
        assertThat(upper).isEqualTo("MEDIUM");
        String fallback = scope.get("missing_result");
        assertThat(fallback).isEqualTo("fallback");
    }

    @Test
    void workflowWithoutScopeCreatesOne() {
        AgentWorkflowResult result = new AgentWorkflowBuilder()
            .transform("a", "b", v -> v)
            .execute("task");
        assertThat(result.scope()).isNotNull();
        assertThat(result.scope().workflowId()).startsWith("workflow-");
    }
}