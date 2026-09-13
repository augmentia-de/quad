package de.augmentia.quad.core.capability.prompt;

import java.util.Map;
import java.util.List;

public class PromptRenderer {
    private final PromptManager promptManager;
    private final ToolListRenderer toolListRenderer;
    private final int indent = 4;
    
    public PromptRenderer(PromptManager promptManager) {
        this.promptManager = promptManager;
        this.toolListRenderer = new ToolListRenderer();
    }
    
    public String render(String key, Object... args) {
        String template = promptManager.get(key);
        if (template == null) {
            template = key;
        }
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 < args.length) {
                template = template.replace("{" + args[i] + "}", String.valueOf(args[i + 1]));
            }
        }
        return template;
    }
    
    public String renderWithPlaceholders(String template, Map<String, String> placeholders) {
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
    
    public String renderWithTools(String base, List<?> toolSpecs) {
        if (toolSpecs == null || toolSpecs.isEmpty()) {
            return base;
        }
        String toolsSection = toolListRenderer.render(toolSpecs);
        return base + "\n\n=== Available Tools ===\n" + toolsSection;
    }
    
    public String renderWithSkills(String base, List<?> skills) {
        if (skills == null || skills.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== Aktivierte Skills ===\n");
        for (Object skill : skills) {
            sb.append("- ").append(skill.toString()).append("\n");
        }
        return base + sb.toString();
    }
}