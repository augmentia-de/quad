package de.augmentia.quad.examples;

import de.augmentia.quad.core.session.AgentSessionState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;



public class TwoStageWorkflowSession extends AgentSessionState {

    private String rawInput;
    private String requirementAnalysis;
    private String architectureDesign;

    private final List<String> identifiedComponents = new CopyOnWriteArrayList<>();

    private String complianceApiKey = "internal-secret-key-12345";

    public String getRawInput() { return rawInput; }
    public void setRawInput(String rawInput) { this.rawInput = rawInput; }

    public String getRequirementAnalysis() { return requirementAnalysis; }
    public void setRequirementAnalysis(String analysis) {
        this.requirementAnalysis = analysis;
        set("stage1.analysis", analysis);
    }

    public String getArchitectureDesign() { return architectureDesign; }
    public void setArchitectureDesign(String design) {
        this.architectureDesign = design;
        set("stage2.design", design);
    }

    public List<String> getIdentifiedComponents() { return identifiedComponents; }
    public void addComponent(String component) {
        this.identifiedComponents.add(component);
        dispatchMutationEvents();
    }

    public String getComplianceApiKey() { return complianceApiKey; }
}