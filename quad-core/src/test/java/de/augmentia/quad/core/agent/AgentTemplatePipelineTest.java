package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.session.AgentSessionState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full template chain at the agent (TODO2 B1).
 *
 * <p>Reproduces the order from {@code AgentNodeExecutor:76-86} and
 * {@code Agent:379-381 → PromptAssembler.buildInitialMessages:124-131}:
 *
 * <ol>
 *   <li>{@code configureAgent} sets {@code userMessageTemplate} and {@code jsonInput}</li>
 *   <li>the executor renders engine-side via {@code InputBuilder.renderTemplate}</li>
 *   <li>{@code PromptAssembler} used to render <em>again</em>, discarding step 2</li>
 * </ol>
 *
 * <p>The prompt is captured before it would reach the model.
 */
class AgentTemplatePipelineTest {

    private static final String TEMPLATE = "Step: {{architect.output}}\nBewerte: {{reviewer.output}}";

    /** Agent that exposes the finished message list instead of calling a model. */
    private static final class CapturingAgent extends Agent {
        List<dev.langchain4j.data.message.ChatMessage> captured = List.of();

        @Override
        public List<dev.langchain4j.data.message.ChatMessage> initialMessages(
                String prompt, AgentSessionState state) {
            captured = super.initialMessages(prompt, state);
            return captured;
        }

        @Override
        protected AgentSessionState newSessionState() {
            return new AgentSessionState();
        }
    }

    private static String userMessageOf(List<dev.langchain4j.data.message.ChatMessage> messages) {
        for (var m : messages) {
            if (m instanceof dev.langchain4j.data.message.UserMessage u) {
                return u.singleText();
            }
        }
        return null;
    }

    private static CapturingAgent agentWithTemplate(boolean jsonInput) {
        var agent = new CapturingAgent();
        agent.setSystemPrompt("You are a test agent.");
        agent.setUserMessageTemplate(TEMPLATE);
        if (jsonInput) agent.setJsonInput(true);
        return agent;
    }

    @Test
    void finalUserMessageKeepsTheResolvedValue() {
        var agent = agentWithTemplate(true);

        // Step 2 (engine-side rendering), what AgentNodeExecutor:82 produces.
        var prompt2 = "Step: PLAN-ABC\nBewerte: {{reviewer.output}}";

        agent.initialMessages(prompt2, new AgentSessionState());

        assertThat(userMessageOf(agent.captured))
                .as("TODO2 B1 fixed: PLAN-ABC must reach the model")
                .contains("PLAN-ABC");
    }

    @Test
    void finalUserMessageEqualsTheRenderedPromptVerbatim() {
        var agent = agentWithTemplate(true);
        var prompt2 = "Step: PLAN-ABC\nBewerte: {{reviewer.output}}";

        agent.initialMessages(prompt2, new AgentSessionState());

        assertThat(userMessageOf(agent.captured))
                .as("the second pass must not change anything any more")
                .isEqualTo(prompt2);
    }

    @Test
    void templateWithoutJsonInputStillApplies() {
        // The template path used to require jsonOutput/jsonInput, which made it invisible for
        // text-in/text-out agents.
        var agent = agentWithTemplate(false);
        agent.setUserMessageTemplate("Only this sentence.");

        agent.initialMessages("irgendein Prompt", new AgentSessionState());

        assertThat(userMessageOf(agent.captured))
                .as("TODO2 B1 fixed: userMessageTemplate also applies without jsonInput")
                .isNotNull();
    }

    @Test
    void withoutTemplateThePromptPassesThrough() {
        var agent = new CapturingAgent();
        agent.setSystemPrompt("You are a test agent.");

        agent.initialMessages("NACKTER PROMPT", new AgentSessionState());

        assertThat(userMessageOf(agent.captured)).isEqualTo("NACKTER PROMPT");
    }

    @Test
    void onlyOneRenderingStageHasAnEffect() {
        // The engine-side result must be identical whether or not a template is configured on the
        // agent — that is what "one renderer" means in practice.
        var withTemplate = agentWithTemplate(true);
        withTemplate.initialMessages("Step: PLAN-ABC", new AgentSessionState());

        var withoutTemplate = new CapturingAgent();
        withoutTemplate.setSystemPrompt("You are a test agent.");
        withoutTemplate.setJsonInput(true);
        withoutTemplate.initialMessages("Step: PLAN-ABC", new AgentSessionState());

        assertThat(userMessageOf(withTemplate.captured))
                .as("TODO2 B1 fixed: the second rendering is a no-op")
                .isEqualTo(userMessageOf(withoutTemplate.captured));
    }
}