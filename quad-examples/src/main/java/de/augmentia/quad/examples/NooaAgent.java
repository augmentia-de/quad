package de.augmentia.quad.examples;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.observability.LoggingHook;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.guardrails.GuardrailPlugin;
import de.augmentia.quad.core.guardrails.GuardrailResult;
import de.augmentia.quad.core.guardrails.Guardrail;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * Ready-to-use, configurable agent — ready for immediate use.
 *
 * <p>This class provides a fully configured agent that can be
 * used directly without manual setup:</p>
 *
 * <pre>{@code
 *   QuadAgent agent = QuadAgent.create();
 *   agent.execute("Briefly explain what an AI assistant is");
 * }</pre>
 *
 * <p>Standard features included:</p>
 * <ul>
 *   <li>LLM configuration from environment variables (OPENAI_API_KEY, OPENAI_MODEL)</li>
 *   <li>Default guardrails against forbidden words</li>
 *   <li>Telemetry via {@link LoggingHook}</li>
 *   <li>Session-based state storage</li>
 * </ul>
 */
public class QuadAgent extends Agent {

    private static final AgentEventPublisher EVENT_PUBLISHER = new AgentEventPublisher();

    static {
        EVENT_PUBLISHER.addEventListener(new LoggingHook()::onEvent);
    }

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    /**
     * Factory method that returns a fully configured agent.
     */
    public static QuadAgent create() {
        QuadAgent agent = new QuadAgent();
        agent.setLlm(ModelFactory.createOpenAiFromEnv());
        agent.setEventPublisher(EVENT_PUBLISHER);
        agent.addHook(new GuardrailPlugin(List.of(defaultGuardrails()), List.of()));
        return agent;
    }

    private static Guardrail defaultGuardrails() {
        return (messages, context) -> {
            String text = messages.stream()
                .map(ChatMessage::toString)
                .reduce("", String::concat)
                .toLowerCase();
            if (text.contains("forbidden") || text.contains("badword")) {
                return GuardrailResult.block("Unwanted content");
            }
            return GuardrailResult.ok();
        };
    }

    @Tool(description = "Demo tool for simple tasks")
    public String ping(String message) {
        return "Pong: " + message;
    }

    public static void main(String[] args) {
        QuadAgent agent = QuadAgent.create();
        String result = agent.execute("Briefly explain what an AI assistant is");
        System.out.println(result);
    }
}