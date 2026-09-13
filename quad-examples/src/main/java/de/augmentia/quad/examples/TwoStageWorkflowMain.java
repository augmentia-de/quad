package de.augmentia.quad.examples;

public class TwoStageWorkflowMain {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Two-Stage Workflow Demo ===");
        System.out.println();

        String rawRequirements = """
            Design a payment processing system for an e-commerce platform.
            It needs to handle high transaction volumes with PCI-DSS compliance.
            Must include fraud detection and real-time analytics.
            """;

        if (args.length > 0) {
            rawRequirements = String.join(" ", args);
        }

        System.out.println("Input Requirements:");
        System.out.println(rawRequirements);
        System.out.println();

        try {
            RequirementAnalysisAgent stage1 = new RequirementAnalysisAgent();
            SolutionArchitectAgent stage2 = new SolutionArchitectAgent();

            stage1.setStateFactory(ag -> new TwoStageWorkflowSession());
            stage2.setStateFactory(ag -> new TwoStageWorkflowSession());

            String analysis = stage1.analyzeRequirements(rawRequirements);

            String design = stage2.designSolution(analysis);

            System.out.println("=== Results ===");
            System.out.println();
            System.out.println("Stage 1 - Requirement Analysis:");
            System.out.println(analysis);
            System.out.println();
            System.out.println("Stage 2 - Architecture Design:");
            System.out.println(design);
            System.out.println();

            System.out.println("=== Workflow Complete ===");

        } catch (Exception e) {
            System.err.println("Error executing workflow: " + e.getMessage());
            e.printStackTrace();
        }
    }
}