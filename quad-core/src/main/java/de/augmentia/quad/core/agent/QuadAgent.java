package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.observability.LoggingHook;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.guardrails.GuardrailPlugin;
import de.augmentia.quad.core.guardrails.GuardrailResult;
import de.augmentia.quad.core.guardrails.Guardrail;
import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * Ready-to-use, configurable agent.
 * <p>
 * Includes out-of-the-box:
 * <ul>
 *   <li>LLM configuration from environment variables</li>
 *   <li>Default guardrails (profanity filter)</li>
 *   <li>Telemetry via LoggingHook</li>
 * </ul>
 * </p>
 * <p>
 * Usage:
 * <pre>
 *   QuadAgent agent = new QuadAgent();
 *   agent.run("Your request");
 * </pre>
 * </p>
 */
public class QuadAgent extends Agent {

    private static AgentEventPublisher eventPublisher;
    private static boolean initialized = false;

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Override
    public String run(String prompt, AgentSessionState state) {
        ensureInitialized();
        return super.run(prompt, state);
    }

    @Override
    public AgentResult executeStructured(String prompt, AgentSessionState state) {
        ensureInitialized();
        return super.executeStructured(prompt, state);
    }

    private void ensureInitialized() {
        if (!initialized) {
            synchronized (QuadAgent.class) {
                if (!initialized) {
                    initializeDefaults();
                    initialized = true;
                }
            }
        }
    }

    private void initializeDefaults() {
        if (llm == null) {
            setLlm(ModelFactory.createOpenAiFromEnv());
        }
        if (eventPublisher == null) {
            eventPublisher = new AgentEventPublisher();
            eventPublisher.addEventListener(new LoggingHook()::onEvent);
            setEventPublisher(eventPublisher);
        }
        addHook(new GuardrailPlugin(List.of(defaultGuardrails()), List.of()));
    }

    private Guardrail defaultGuardrails() {
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

    public QuadAgent withLogging() {
        if (eventPublisher == null) {
            eventPublisher = new AgentEventPublisher();
        }
        eventPublisher.addEventListener(new LoggingHook()::onEvent);
        setEventPublisher(eventPublisher);
        return this;
    }

    public QuadAgent withGuardrail(Guardrail guardrail) {
        addHook(new GuardrailPlugin(List.of(guardrail), List.of()));
        return this;
    }

    public AgentEventPublisher getEventPublisher() {
        return eventPublisher;
    }

    public static void main(String[] args) {
        QuadAgent agent = new QuadAgent();
        String result = agent.execute("Briefly explain what an AI assistant is");
        System.out.println(result);
    }
}