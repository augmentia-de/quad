package de.augmentia.quad.core.guardrails;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.hook.plugin.Plugin;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * Plugin that applies input and output guardrails to agent interactions.
 * Registered as a hook in the pipeline (see {@link Agent#addHook}).
 */
public class GuardrailPlugin implements Plugin {

    private final List<Guardrail> inputGuardrails;
    private final List<Guardrail> outputGuardrails;
    private final BlockAction blockAction;
    private final String fallbackMessage;
    private Agent agent;

    public GuardrailPlugin(List<Guardrail> inputGuardrails, List<Guardrail> outputGuardrails) {
        this(inputGuardrails, outputGuardrails, BlockAction.FALLBACK, "I cannot process this request.");
    }

    public GuardrailPlugin(List<Guardrail> inputGuardrails, List<Guardrail> outputGuardrails,
                           BlockAction blockAction, String fallbackMessage) {
        this.inputGuardrails = inputGuardrails;
        this.outputGuardrails = outputGuardrails;
        this.blockAction = blockAction;
        this.fallbackMessage = fallbackMessage;
    }

    @Override
    public String name() {
        return "guardrails";
    }

    @Override
    public void initAgent(Agent agent) {
        this.agent = agent;
    }

    @Override
    public List<Guardrail> getInputGuardrails() {
        return inputGuardrails;
    }

    @Override
    public List<Guardrail> getOutputGuardrails() {
        return outputGuardrails;
    }

    @Override
    public BlockAction getBlockAction() {
        return blockAction;
    }

    @Override
    public String getFallbackMessage() {
        return fallbackMessage;
    }

    public Agent agent() {
        return agent;
    }

    public static GuardrailResult evaluate(List<Guardrail> guardrails, List<ChatMessage> messages, String context) {
        for (Guardrail guardrail : guardrails) {
            GuardrailResult result = guardrail.validate(messages, context);
            if (!result.pass()) return result;
        }
        return GuardrailResult.ok();
    }
}
