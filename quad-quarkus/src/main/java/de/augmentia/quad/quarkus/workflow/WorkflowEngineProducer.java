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
 * Baut die containerfreie Engine auf und bringt sie als CDI-Bean in die App.
 *
 * <p>Die Engine-Klassen haben bewusst keinen Konstruktor mit Injection - sie
 * sind mit {@code new} benutzbar und im Spring-Boot-Worker genauso lauffaehig
 * wie hier. Quarkus braucht aber trotzdem eine Bean, und genau eine: diesen
 * Producer.
 *
 * <p>Damit ist die Verdrahtung an einer Stelle sichtbar. Vorher war sie ueber
 * 21 {@code @ApplicationScoped}-Klassen mit {@code @Inject}-Feldern verteilt,
 * und niemand konnte ohne Container sagen, wer wen braucht.
 *
 * <p>Die Executoren werden hier explizit aufgezaehlt. Ein neuer Knotentyp wird
 * also an genau zwei Stellen sichtbar: seine Klasse und diese Liste.
 */
@ApplicationScoped
public class WorkflowEngineProducer {

    @Inject WorkflowRunPort runPort;
    @Inject RunEventPort runEvents;
    @Inject StepCheckpointPort checkpoints;
    @Inject WorkflowDefinitionPort definitions;
    @Inject AgentInvocationPort agents;

    /**
     * Konfiguration aus MicroProfile-Config - nur hier, weil dies die einzige
     * Klasse ist, die den Container kennt. Die Engine bekommt einfache Zahlen.
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