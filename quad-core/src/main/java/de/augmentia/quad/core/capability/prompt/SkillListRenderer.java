package de.augmentia.quad.core.capability.prompt;

import java.util.List;

public class SkillListRenderer {
    
    public String render(List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return "Keine Skills aktiviert.";
        }
        
        StringBuilder sb = new StringBuilder("=== Aktivierte Skills ===\n");
        for (String skill : skills) {
            sb.append("- ").append(skill).append("\n");
        }
        return sb.toString().trim();
    }
    
    public String renderWithSkills(String base, List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return base;
        }
        return base + "\n\n" + render(skills);
    }
}