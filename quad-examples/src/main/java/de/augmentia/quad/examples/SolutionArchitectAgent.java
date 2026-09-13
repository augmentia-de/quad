package de.augmentia.quad.examples;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;

public class SolutionArchitectAgent extends Agent {

    public SolutionArchitectAgent() {
        initLlm();
    }

    @Override
    protected AgentSessionState newSessionState() {
        return new TwoStageWorkflowSession();
    }

    @Tool(description = "Checks system capacity and infrastructure cost for a component type")
    public String checkCapacity(AgentSessionState state, @Param("componentType") String componentType) {
        return switch (componentType.toUpperCase()) {
            case "DATABASE" -> "PostgreSQL HA Cluster - Tier 1 (Cost: Medium)";
            case "QUEUE" -> "Apache Kafka Cluster - 3 Nodes (Cost: High)";
            default -> "Standard Container Instance (Cost: Low)";
        };
    }

    public String designSolution(String requirementAnalysis) {
        TwoStageWorkflowSession session = (TwoStageWorkflowSession) CurrentSession.getCurrent();
        return designSolution(session, requirementAnalysis);
    }

    public String designSolution(TwoStageWorkflowSession session, String requirementAnalysis) {

        String prompt = """
            You are a Principal Enterprise Architect.
            Based on Stage 1 Requirement Analysis below, design a system architecture.
            Use 'checkCapacity' to select compliant infrastructure components.
            
            --- STAGE 1 ANALYSIS ---
            %s
            """.formatted(requirementAnalysis);

        String designOutput = execute(prompt);

        if (session != null) {
            session.setArchitectureDesign(designOutput);
        }
        return designOutput;

    }
}