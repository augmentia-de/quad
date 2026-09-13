package de.augmentia.quad.core.session.memory;

import java.util.List;

import de.augmentia.quad.core.session.AgentSessionState;
import dev.langchain4j.data.message.ChatMessage;

public class SagaAwareConversationManager {
    public List<ChatMessage> injectSagaRollbackNotice(List<ChatMessage> messages, AgentSessionState state) {
        if (state != null && state.isSagaFailed()) {
            String rollbackNotice = "[System: Saga compensation completed. "
                + "The previous operation was rolled back due to a failure. "
                + "Please retry with corrected inputs.]";
        }
        return messages;
    }

    public String getRollbackContext(AgentSessionState state) {
        if (state != null && !state.getSagaLog().isEmpty() && state.isSagaFailed()) {
            return "Previous saga operations were rolled back. Current compensations applied: "
                + state.getSagaLog().size();
        }
        return "";
    }
}