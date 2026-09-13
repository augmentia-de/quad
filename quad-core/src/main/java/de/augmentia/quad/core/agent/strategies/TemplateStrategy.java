package de.augmentia.quad.core.agent.strategies;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.Map;

/**
 * Template strategy: Prompt templating with {{variable}} substitution.
 * <p>
 * Source: Python {@code src/quad/strategies/template.py}
 */
public class TemplateStrategy {

    private final ChatModel chatModel;

    public TemplateStrategy(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String render(String template, Map<String, String> variables) {
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }

    public String execute(String template, Map<String, String> variables) {
        String rendered = render(template, variables);
        return chatModel.chat(ChatRequest.builder()
                .messages(java.util.List.of(UserMessage.from(rendered)))
                .build()).aiMessage().text();
    }
}
