package de.augmentia.quad.quarkus.agent.runtime;

import dev.langchain4j.model.chat.ChatModel;

public class QuarkusAgentRuntime {
    private final ChatModel llm;

    public QuarkusAgentRuntime(ChatModel llm) {
        this.llm = llm;
    }

    public ChatModel getLlm() {
        return llm;
    }
}