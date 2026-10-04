package de.augmentia.quad.quarkus.workflow;

import de.augmentia.quad.workflow.engine.WorkflowEngine;
import de.augmentia.quad.workflow.engine.WorkflowScheduler;
import de.augmentia.quad.workflow.engine.context.InputBuilder;
import de.augmentia.quad.workflow.engine.internal.RunStateWriter;
import de.augmentia.quad.workflow.engine.internal.WorkflowSupport;
import de.augmentia.quad.workflow.engine.node.NodeExecutor;
import de.augmentia.quad.workflow.engine.node.NodeExecutorRegistry;
import de.augmentia.quad.workflow.engine.node.executor.*;
import de.augmentia.quad.workflow.engine.port.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Assembles the container-free engine and brings it into the app as a CDI bean.
 *
 * <p>The engine classes deliberately have no constructor with injection — they
 * are usable with {@code new} and just as capable in the Spring Boot worker
 * as here. Quarkus nevertheless needs a bean, and exactly one: this
 * producer.
 *
 * <p>That makes the wiring visible in one place. Previously it was spread across
 * 21 {@code @ApplicationScoped} classes with {@code @Inject} fields,
 * and nobody could say without a container who needs whom.
 *
 * <p>The executors are explicitly enumerated here. A new node type thus becomes
 * visible in exactly two places: its class and this list.
 */
@ApplicationScoped
public class WorkflowEngineProducer {

    @Inject WorkflowRunPort runPort;
    @Inject RunEventPort runEvents;
    @Inject StepCheckpointPort checkpoints;
    @Inject WorkflowDefinitionPort definitions;
    @Inject AgentInvocationPort agents;

    /**
     * Configuration from MicroProfile Config — only here, because this is the only
     * class that knows the container. The engine gets plain numbers.
     */
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "quad.memory.max-messages", defaultValue = "50")
    int memoryMaxMessages;

    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "quad.workflow.default-timeout-ms", defaultValue = "600000")
    long defaultTimeoutMs;

    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "quad.messaging.workflow.timeout-ms", defaultValue = "30000")
    long messagingTimeoutMs;

    @Produces
    @ApplicationScoped
    public WorkflowEngine workflowEngine() {
        RunStateWriter runWriter = new RunStateWriter(runPort, checkpoints);
        WorkflowSupport support = new WorkflowSupport(
                runPort, runWriter, memoryMaxMessages, defaultTimeoutMs, messagingTimeoutMs);
        InputBuilder inputBuilder = new InputBuilder(definitions);

        AgentNodeExecutor agentExecutor = new AgentNodeExecutor(
                agents, inputBuilder, support, runWriter, runEvents);

        List<NodeExecutor> executors = List.of(
                agentExecutor,
                new AsyncNodeExecutor(support, agentExecutor, runWriter),
                new ConditionalNodeExecutor(inputBuilder, runWriter),
                new ForkNodeExecutor(runWriter),
                new JoinNodeExecutor(runWriter),
                new LoopNodeExecutor(runWriter),
                new NestedWorkflowNodeExecutor(definitions, runWriter));

        return new WorkflowEngine(
                definitions,
                support,
                new WorkflowScheduler(support, runWriter),
                new NodeExecutorRegistry(executors),
                agentExecutor,
                runWriter,
                runPort,
                runEvents,
                checkpoints);
    }
}