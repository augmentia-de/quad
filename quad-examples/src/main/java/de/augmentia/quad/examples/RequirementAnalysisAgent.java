package de.augmentia.quad.examples;

import java.util.List;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;

public class RequirementAnalysisAgent extends Agent {

    public RequirementAnalysisAgent() {
        initLlm();
    }

    @Override
    protected AgentSessionState newSessionState() {
        return new TwoStageWorkflowSession();
    }

    @Tool(description = "Validates whether a proposed requirement tag meets enterprise policies")
    public boolean validatePolicyTag(AgentSessionState state, @Param("tag") String tag) {
        TwoStageWorkflowSession session = (TwoStageWorkflowSession) state;
        boolean isValid = !tag.equalsIgnoreCase("DEPRECATED");
        if (isValid) {
            session.addFinding("Valid policy tag detected: " + tag);
        }
        return isValid;
    }

    @Tool(description = "Extracts functional domain tags from input text")
    public List<String> extractDomainTags(AgentSessionState state, @Param("text") String text) {
        if (text.contains("payment")) {
            return List.of("FINANCE", "PCI-DSS", "HIGH-SECURITY");
        }
        return List.of("GENERAL-IT", "STANDARD");
    }

    public String analyzeRequirements(String rawRequirements) {
        CurrentSession.setCurrent((TwoStageWorkflowSession) new TwoStageWorkflowSession());
        
        String prompt = """
            Analyze the following requirement description.
            Use available tools to validate policy tags and extract domain categories.
            
            Requirement Description:
            %s
            """.formatted(rawRequirements);

        String result = execute(prompt);

        TwoStageWorkflowSession session = (TwoStageWorkflowSession) CurrentSession.getCurrent();
        if (session != null) {
            session.setRawInput(rawRequirements);
            session.setRequirementAnalysis(result);
        }
        return result;
    }

}