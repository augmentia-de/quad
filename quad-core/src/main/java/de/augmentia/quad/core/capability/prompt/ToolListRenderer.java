package de.augmentia.quad.core.capability.prompt;

import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.List;

public class ToolListRenderer {

    public ToolListRenderer() {
    }

    public String render(List<?> tools) {
        if (tools == null || tools.isEmpty()) {
            return "No tools available";
        }
        StringBuilder sb = new StringBuilder();
        for (Object obj : tools) {
            if (obj instanceof ToolSpecification spec) {
                sb.append("- ")
                  .append(spec.name())
                  .append(": ")
                  .append(spec.description())
                  .append("\n  Parameter: ")
                  .append(renderParameters(spec.parameters()))
                  .append("\n\n");
            } else {
                sb.append("- ").append(obj.toString()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    public String renderWithTools(String template, List<?> toolSpecs) {
        if (toolSpecs == null || toolSpecs.isEmpty()) {
            return template;
        }
        String toolsSection = render(toolSpecs);
        return template.replace("{tools}", toolsSection);
    }

    private String renderParameters(Object parameters) {
        if (parameters == null) {
            return "Keine";
        }
        if (parameters instanceof dev.langchain4j.model.chat.request.json.JsonObjectSchema schema) {
            return schema.properties().entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue().description())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Keine");
        }
        return parameters.toString();
    }
}