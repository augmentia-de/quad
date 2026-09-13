package de.augmentia.quad.core.agent;

import de.augmentia.quad.core.message.Message;
import java.util.List;

public record AgentResult(
    String sessionId,
    String finalAnswer,
    List<Message> generatedMessages,
    ExecutionMetrics metrics,
    StopReason stopReason,
    String structuredOutput
) {
    public AgentResult(String sessionId, String finalAnswer, List<Message> generatedMessages,
                       ExecutionMetrics metrics, StopReason stopReason) {
        this(sessionId, finalAnswer, generatedMessages, metrics, stopReason, null);
    }

    public AgentResult(String finalAnswer, String structuredOutput, long durationMs) {
        this(null, finalAnswer, List.of(), new ExecutionMetrics(durationMs, 0, 0, 0),
             StopReason.COMPLETED, structuredOutput);
    }

    public boolean hasStructuredOutput() {
        return structuredOutput != null && !structuredOutput.isBlank();
    }
}
