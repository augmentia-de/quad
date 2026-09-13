package de.augmentia.quad.examples;



import de.augmentia.quad.core.agent.SessionStateFactory;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class TwoStageWorkflowService {

    @Inject RequirementAnalysisAgent stage1Agent;
    @Inject SolutionArchitectAgent stage2Agent;
    @Inject SessionStateFactory stateFactory;

    public TwoStageWorkflowSession executeWorkflow(String rawRequirements) {
        TwoStageWorkflowSession session = (TwoStageWorkflowSession) stateFactory.create(stage1Agent);
        session.setRawInput(rawRequirements);

        String analysis = stage1Agent.analyzeRequirements(rawRequirements);

        stage2Agent.designSolution(analysis);

        return session;
    }
}