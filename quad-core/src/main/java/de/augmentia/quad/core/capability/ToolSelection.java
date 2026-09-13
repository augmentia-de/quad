package de.augmentia.quad.core.capability;

import java.util.List;

public class ToolSelection {
    private String analysis;
    private List<String> recommendedTools;
    private List<String> recommendedSkills;
    private List<ToolEnrichment> toolEnrichments;
    private String reasoning;

    public ToolSelection() {}

    public ToolSelection(String analysis, List<String> recommendedTools,
                         List<String> recommendedSkills, List<ToolEnrichment> toolEnrichments,
                         String reasoning) {
        this.analysis = analysis;
        this.recommendedTools = recommendedTools;
        this.recommendedSkills = recommendedSkills;
        this.toolEnrichments = toolEnrichments;
        this.reasoning = reasoning;
    }

    public String analysis() { return analysis; }
    public List<String> recommendedTools() { return recommendedTools; }
    public List<String> recommendedSkills() { return recommendedSkills; }
    public List<ToolEnrichment> toolEnrichments() { return toolEnrichments; }
    public String reasoning() { return reasoning; }

    public void setAnalysis(String analysis) { this.analysis = analysis; }
    public void setRecommendedTools(List<String> recommendedTools) { this.recommendedTools = recommendedTools; }
    public void setRecommendedSkills(List<String> recommendedSkills) { this.recommendedSkills = recommendedSkills; }
    public void setToolEnrichments(List<ToolEnrichment> toolEnrichments) { this.toolEnrichments = toolEnrichments; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    public static record ToolEnrichment(String toolName, String enrichment, String context) {}
}