package de.augmentia.quad.core.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class AgentMetrics {

    private final Timer executionDuration;
    private final Counter llmCalls;
    private final Counter toolExecutions;
    private final DistributionSummary promptTokens;
    private final DistributionSummary completionTokens;
    private final Counter errors;

    public AgentMetrics(MeterRegistry registry) {
        this.executionDuration = Timer.builder("agent.execution.duration")
            .description("Dauer pro Agent-Durchlauf")
            .register(registry);
        this.llmCalls = Counter.builder("agent.llm.calls")
            .description("Number of LLM calls")
            .register(registry);
        this.toolExecutions = Counter.builder("agent.tool.executions")
            .description("Number of tool executions")
            .register(registry);
        this.promptTokens = DistributionSummary.builder("agent.tokens.prompt")
            .description("Prompt-Tokens")
            .register(registry);
        this.completionTokens = DistributionSummary.builder("agent.tokens.completion")
            .description("Completion-Tokens")
            .register(registry);
        this.errors = Counter.builder("agent.errors")
            .description("Fehler nach Typ")
            .register(registry);
    }

    public Timer getExecutionDuration() { return executionDuration; }
    public Counter getLlmCalls() { return llmCalls; }
    public Counter getToolExecutions() { return toolExecutions; }
    public DistributionSummary getPromptTokens() { return promptTokens; }
    public DistributionSummary getCompletionTokens() { return completionTokens; }
    public Counter getErrors() { return errors; }
}
