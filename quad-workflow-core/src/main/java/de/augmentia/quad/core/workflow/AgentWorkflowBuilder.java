package de.augmentia.quad.core.workflow;
import de.augmentia.quad.core.scope.AgentScope;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentResult;
import de.augmentia.quad.core.config.StructuredInputConfig;
import de.augmentia.quad.core.config.StructuredOutputConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Builder for typed agent pipelines with support for loops, conditionals, and nested workflows.
 * <p>
 * <h3>Sequential Steps</h3>
 * Chains agents with targeted input/output mapping:
 * <pre>{@code
 * var workflow = new AgentWorkflowBuilder()
 *     .step("analyze", analyzer,
 *         StructuredOutputConfig.staticModel(TaskAnalysis.class), "analysis")
 *     .transform("analysis", "tools_for_planner",
 *         analysis -> FieldExtractor.extract(analysis, "recommendedTools"))
 *     .step("plan", planner,
 *         StructuredInputConfig.fromTemplate("Plan with: {{tools_for_planner}}"),
 *         StructuredOutputConfig.staticModel(ImplementationPlan.class), "plan");
 *
 * var result = workflow.execute("Build a REST API");
 * TaskAnalysis analysis = result.scope().get("analysis");
 * }</pre>
 *
 * <h3>Loops</h3>
 * Repeated execution with exit conditions (equivalent to langchain4j-agentic's {@code @LoopAgent}):
 * <pre>{@code
 * var workflow = new AgentWorkflowBuilder()
 *     .loop("planReview", LoopNode.builder()
 *         .nodeId("planReview")
 *         .addStep("planner", planner)
 *         .addTransform("planText", "score", evaluator::score)
 *         .maxIterations(5)
 *         .exitCondition(LambdaExitCondition.scoreGte("score", 0.8))
 *         .outputKey("reviewResult")
 *         .build());
 * }</pre>
 *
 * <h3>Conditionals</h3>
 * Branching based on predicates:
 * <pre>{@code
 * var workflow = new AgentWorkflowBuilder()
 *     .conditional("scoreCheck", ConditionalNode.builder("scoreCheck")
 *         .predicate(scope -> ((Double) scope.get("score")) >= 0.8)
 *         .branch("approved").addStep("implementer", implAgent).build()
 *         .branch("rejected").addStep("refiner", refinerAgent).build());
 * }</pre>
 */
public class AgentWorkflowBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<AgentStep> steps = new ArrayList<>();
    private final List<WorkflowNode> specialNodes = new ArrayList<>();
    private AgentScope scope;
    private String initialPromptKey = "initialPrompt";
    private String previousOutputKey = "previousOutput";

    public AgentWorkflowBuilder withScope(AgentScope scope) {
        this.scope = scope;
        return this;
    }

    // ──────────────────────────────────────────────────────────────
    //  Sequential Steps
    // ──────────────────────────────────────────────────────────────

    /** Adds an agent step with automatic typed output storage under {@code outputKey}. */
    public AgentWorkflowBuilder step(String name, Agent agent,
                                      StructuredOutputConfig outputConfig, String outputKey) {
        steps.add(AgentStep.forAgent(agent, outputConfig, outputKey));
        return this;
    }

    /** Adds an agent step with a rendered input template and typed output storage. */
    public AgentWorkflowBuilder step(String name, Agent agent,
                                      StructuredInputConfig inputConfig,
                                      StructuredOutputConfig outputConfig, String outputKey) {
        AgentStep step = AgentStep.forAgent(agent, outputConfig, outputKey);
        step.inputConfig = inputConfig;
        steps.add(step);
        return this;
    }

    /**
     * Adds an agent step with separate system and user message templates.
     * Both are rendered from templates before execution.
     */
    public AgentWorkflowBuilder step(String name, Agent agent,
                                      StructuredInputConfig systemMessage,
                                      StructuredInputConfig userMessage,
                                      StructuredOutputConfig outputConfig, String outputKey) {
        AgentStep step = AgentStep.forAgent(agent, outputConfig, outputKey);
        step.systemMessage = systemMessage;
        step.userMessage = userMessage;
        steps.add(step);
        return this;
    }

    /**
     * Adds an agent step with JSON input support.
     * The agent's {@code jsonInput} flag is set to true, and the
     * {@code userMessageTemplate} is used to render the user message from the input JSON.
     */
    public AgentWorkflowBuilder stepWithJsonInput(String name, Agent agent,
                                                    String userMessageTemplate,
                                                    StructuredOutputConfig outputConfig, String outputKey) {
        agent.setJsonInput(true);
        agent.setUserMessageTemplate(userMessageTemplate);
        AgentStep step = AgentStep.forAgent(agent, outputConfig, outputKey);
        step.jsonInput = true;
        steps.add(step);
        return this;
    }

    /**
     * Adds a pure transformation step.
     * {@code fromKey} may contain a dotted field path
     * (e.g. {@code analysis.recommendedTools}) resolved via {@link FieldExtractor}.
     */
    public AgentWorkflowBuilder transform(String fromKey, String toKey,
                                           Function<Object, Object> transformer) {
        if (toKey == null || toKey.isBlank()) {
            throw new IllegalArgumentException("toKey must not be blank");
        }
        steps.add(AgentStep.forTransform(transformer, fromKey, toKey));
        return this;
    }

    // ──────────────────────────────────────────────────────────────
    //  Loops — use LoopNode.Builder directly
    // ──────────────────────────────────────────────────────────────

    /**
     * Adds a looping node that repeats its body until the exit condition is met
     * or max iterations is reached.
     *
     * @param loopId unique identifier for the loop (convenience: wraps in LoopNode.builder())
     * @return LoopNode.Builder for configuring the loop
     */
    public LoopNode.Builder loop(String loopId) {
        return LoopNode.builder().nodeId(loopId);
    }

    /**
     * Adds a pre-built LoopNode to this workflow.
     */
    public AgentWorkflowBuilder addLoop(LoopNode loop) {
        specialNodes.add(loop);
        return this;
    }

    // ──────────────────────────────────────────────────────────────
    //  Conditionals — use ConditionalNode.Builder directly
    // ──────────────────────────────────────────────────────────────

    /**
     * Adds a conditional branching node that routes execution based on a predicate.
     *
     * @param nodeId unique identifier for the conditional
     * @return ConditionalNode.Builder for configuring branches
     */
    public ConditionalNode.Builder conditional(String nodeId) {
        return ConditionalNode.builder(nodeId);
    }

    /**
     * Adds a pre-built ConditionalNode to this workflow.
     */
    public AgentWorkflowBuilder addConditional(ConditionalNode conditional) {
        specialNodes.add(conditional);
        return this;
    }

    // ──────────────────────────────────────────────────────────────
    //  Nested Workflows
    // ──────────────────────────────────────────────────────────────

    /** Adds a nested workflow node as a single step in the current workflow. */
    public AgentWorkflowBuilder addNode(NestedWorkflowNode nested) {
        specialNodes.add(nested);
        return this;
    }

    // ──────────────────────────────────────────────────────────────
    //  Build & Execute
    // ──────────────────────────────────────────────────────────────

    /**
     * Builds and returns the complete workflow configuration.
     */
    public AgentWorkflowConfig build() {
        List<LoopNode> loops = new ArrayList<>();
        List<ConditionalNode> conditionals = new ArrayList<>();
        List<NestedWorkflowNode> nestedWorkflows = new ArrayList<>();
        for (var n : specialNodes) {
            if (n instanceof LoopNode ln) loops.add(ln);
            else if (n instanceof ConditionalNode cn) conditionals.add(cn);
            else if (n instanceof NestedWorkflowNode nw) nestedWorkflows.add(nw);
        }
        AgentWorkflowConfig.Builder cfg = AgentWorkflowConfig.builder()
            .workflowId("workflow-" + System.currentTimeMillis())
            .steps(steps);
        for (LoopNode l : loops) cfg.addLoop(l);
        for (ConditionalNode c : conditionals) cfg.addConditional(c);
        for (NestedWorkflowNode n : nestedWorkflows) cfg.addNested(n);
        return cfg.build();
    }

    /**
     * Executes the workflow with the given initial prompt.
     * Handles sequential steps, loops, conditionals, and nested workflows.
     */
    public AgentWorkflowResult execute(String initialPrompt) {
        if (scope == null) {
            scope = new AgentScope(null);
        }

        String lastText = initialPrompt;
        AgentResult lastResult = null;

        // Phase 1: Sequential steps
        for (var node : steps) {
            if (node.transformer != null) {
                Object value = resolve(node.fromKey);
                scope.put(node.toKey, node.transformer.apply(value));
                continue;
            }

            if (node.systemMessage != null && node.userMessage != null) {
                var values = scope.templateVariables();
                values.putIfAbsent(initialPromptKey, initialPrompt);
                values.putIfAbsent(previousOutputKey, lastText);
                String sysMsg = node.systemMessage.render(values);
                String usrMsg = node.userMessage.render(values);
                if (node.outputConfig != null) node.agent.setStructuredOutputConfig(node.outputConfig);
                lastResult = node.agent.executeStructured(sysMsg, usrMsg, node.agent.createSessionState());
            } else if (node.jsonInput) {
                String prompt = prepareInput(node, initialPrompt, lastText);
                if (node.outputConfig != null) node.agent.setStructuredOutputConfig(node.outputConfig);
                lastResult = node.agent.executeStructured(prompt);
            } else {
                String prompt = prepareInput(node, initialPrompt, lastText);
                if (node.outputConfig != null) node.agent.setStructuredOutputConfig(node.outputConfig);
                lastResult = node.agent.executeStructured(prompt);
            }

            if (node.outputKey != null) {
                Object typed = typedOutput(node.outputConfig, lastResult);
                scope.put(node.outputKey, typed);
                scope.put(node.outputKey + "_text", lastResult.finalAnswer());
            }
            lastText = lastResult.finalAnswer() != null ? lastResult.finalAnswer() : lastText;
        }

        // Phase 2: Special nodes (loops, conditionals, nested)
        for (var node : specialNodes) {
            if (node instanceof LoopNode loop) {
                lastText = executeLoop(loop, initialPrompt, lastText);
                lastResult = null;
            } else if (node instanceof ConditionalNode conditional) {
                lastText = executeConditional(conditional, lastText);
                lastResult = null;
            } else if (node instanceof NestedWorkflowNode nested) {
                lastText = executeNested(nested, lastText);
                lastResult = null;
            }
        }

        return new AgentWorkflowResult(scope, lastResult);
    }

    // ──────────────────────────────────────────────────────────────
    //  Loop Execution
    // ──────────────────────────────────────────────────────────────

    private String executeLoop(LoopNode loop, String initialPrompt, String lastText) {
        int iteration = 0;
        boolean exitedEarly = false;

        while (iteration < loop.maxIterations()) {
            iteration++;

            for (Object step : loop.loopSteps().steps()) {
                if (step instanceof LoopSteps.AgentStep agentStep) {
                    var values = scope.templateVariables();
                    values.putIfAbsent(previousOutputKey, lastText);
                    if (agentStep.outputConfig() != null) {
                        agentStep.agent().setStructuredOutputConfig(agentStep.outputConfig());
                    }
                    AgentResult result = agentStep.agent().executeStructured(lastText);
                    if (agentStep.outputKey() != null) {
                        scope.put(agentStep.outputKey(), typedOutput(agentStep.outputConfig(), result));
                        scope.put(agentStep.outputKey() + "_text", result.finalAnswer());
                    }
                    lastText = result.finalAnswer() != null ? result.finalAnswer() : lastText;
                } else if (step instanceof LoopSteps.TransformStep ts) {
                    Object value = resolve(ts.fromKey());
                    scope.put(ts.toKey(), ts.transformer().apply(value));
                }
            }

            if (loop.exitCondition() != null && loop.exitCondition().shouldExit(scope)) {
                exitedEarly = true;
                break;
            }
        }

        if (loop.outputKey() != null) {
            scope.put(loop.outputKey() + "_summary",
                String.format("Loop '%s' completed after %d iteration(s)%s",
                    loop.id(), iteration,
                    exitedEarly ? " (exit condition met)" : " (max iterations reached)"));
        }

        if (loop.failureOutputKey() != null && !exitedEarly) {
            scope.put(loop.failureOutputKey() + "_text", lastText);
        }

        return lastText;
    }

    // ──────────────────────────────────────────────────────────────
    //  Conditional Execution
    // ──────────────────────────────────────────────────────────────

    private String executeConditional(ConditionalNode conditional, String lastText) {
        String selectedBranch = selectBranch(conditional);
        List<WorkflowNode> branch = conditional.branches().get(selectedBranch);
        if (branch == null) branch = conditional.defaultBranch();
        if (branch == null) return lastText;

        for (var node : branch) {
            if (node instanceof AgentStep step) {
                AgentResult result;
                if (step.transformer != null) {
                    Object value = resolve(step.fromKey);
                    scope.put(step.toKey, step.transformer.apply(value));
                    lastText = step.toKey;
                    continue;
                }
                // Execute the branch step (same logic as main workflow)
                String prompt = prepareInput(step, "", lastText);
                if (step.outputConfig != null) step.agent.setStructuredOutputConfig(step.outputConfig);
                AgentResult branchResult = step.agent.executeStructured(prompt);
                if (step.outputKey != null) {
                    scope.put(step.outputKey, typedOutput(step.outputConfig, branchResult));
                    scope.put(step.outputKey + "_text", branchResult.finalAnswer());
                }
                lastText = branchResult.finalAnswer();
            }
        }
        return lastText;
    }

    private String selectBranch(ConditionalNode conditional) {
        if (conditional.conditionType() == ConditionalNode.ConditionType.PREDICATE) {
            @SuppressWarnings("unchecked")
            Function<AgentScope, Boolean> pred =
                (Function<AgentScope, Boolean>) conditional.conditionValue();
            boolean matches = pred.apply(scope);
            return matches ? conditional.matchedKey() : conditional.notMatchedKey();
        } else if (conditional.conditionType() == ConditionalNode.ConditionType.EXIT_CONDITION) {
            ExitCondition cond = (ExitCondition) conditional.conditionValue();
            boolean matches = cond.shouldExit(scope);
            return matches ? conditional.matchedKey() : conditional.notMatchedKey();
        }
        // Fallback: first branch key or default
        if (!conditional.branches().isEmpty()) {
            return conditional.branches().keySet().iterator().next();
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────
    //  Nested Workflow Execution
    // ──────────────────────────────────────────────────────────────

    private String executeNested(NestedWorkflowNode nested, String lastText) {
        if (nested.inputMapping().isEmpty()) {
            scope.put("_nested_" + nested.nodeId() + "_last_text", "Executing: " + nested.workflowId());
            return "Executing nested workflow: " + nested.workflowId();
        }
        StringBuilder sb = new StringBuilder("Nested workflow '" + nested.workflowId() + "'\n");
        for (var entry : nested.inputMapping().entrySet()) {
            Object value = scope.get(entry.getKey());
            sb.append("  ").append(entry.getKey()).append(": ").append(value).append("\n");
        }
        scope.put("_nested_" + nested.nodeId() + "_last_text", sb.toString());
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────

    private String prepareInput(AgentStep step, String initialPrompt, String lastText) {
        if (step.inputConfig == null) return lastText;
        var values = scope.templateVariables();
        values.putIfAbsent(initialPromptKey, initialPrompt);
        values.putIfAbsent(previousOutputKey, lastText);
        return step.inputConfig.render(values);
    }

    private Object resolve(String fromKey) {
        if (fromKey == null) return null;
        int dot = fromKey.indexOf('.');
        if (dot > 0) return scope.extract(fromKey.substring(0, dot), fromKey.substring(dot + 1));
        return scope.get(fromKey);
    }

    private Object typedOutput(StructuredOutputConfig outputConfig, AgentResult result) {
        if (outputConfig == null || !outputConfig.isEnabled()
            || outputConfig.outputClass() == null || !result.hasStructuredOutput()) {
            return result.structuredOutput();
        }
        try {
            return MAPPER.readValue(result.structuredOutput(), outputConfig.outputClass());
        } catch (Exception e) {
            return result.structuredOutput();
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Internal AgentStep record
    // ──────────────────────────────────────────────────────────────

    static class AgentStep implements WorkflowNode {
        final String nodeId;
        final Agent agent;
        StructuredInputConfig inputConfig;
        StructuredOutputConfig outputConfig;
        String outputKey;
        Function<Object, Object> transformer;
        String fromKey;
        String toKey;
        StructuredInputConfig systemMessage;
        StructuredInputConfig userMessage;
        boolean jsonInput;

        AgentStep(String nodeId, Agent agent, StructuredInputConfig inputConfig,
                  StructuredOutputConfig outputConfig, String outputKey,
                  Function<Object, Object> transformer, String fromKey, String toKey,
                  StructuredInputConfig systemMessage, StructuredInputConfig userMessage,
                  boolean jsonInput) {
            this.nodeId = nodeId;
            this.agent = agent;
            this.inputConfig = inputConfig;
            this.outputConfig = outputConfig;
            this.outputKey = outputKey;
            this.transformer = transformer;
            this.fromKey = fromKey;
            this.toKey = toKey;
            this.systemMessage = systemMessage;
            this.userMessage = userMessage;
            this.jsonInput = jsonInput;
        }

        Agent agent() { return agent; }

        @Override
        public String id() { return nodeId; }

        static AgentStep forAgent(Agent agent, StructuredOutputConfig outputConfig, String outputKey) {
            return new AgentStep(
                "step-" + System.nanoTime() % 100000,
                agent, null, outputConfig, outputKey,
                null, null, null, null, null, false);
        }

        static AgentStep forTransform(Function<Object, Object> transformer,
                                       String fromKey, String toKey) {
            return new AgentStep(
                "transform-" + fromKey + "-to-" + toKey,
                null, null, null, toKey,
                transformer, fromKey, toKey, null, null, false);
        }
    }
}
