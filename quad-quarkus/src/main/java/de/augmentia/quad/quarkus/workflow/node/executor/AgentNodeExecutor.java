package de.augmentia.quad.quarkus.workflow.node.executor;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.messaging.QuadMessage;
import de.augmentia.quad.core.config.StructuredOutputConfig;
import de.augmentia.quad.quarkus.messaging.ChannelAgentFactory;
import de.augmentia.quad.quarkus.messaging.MessagingRouter;
import de.augmentia.quad.quarkus.persistence.RunStore;
import de.augmentia.quad.quarkus.persistence.SessionStore;
import de.augmentia.quad.quarkus.workflow.context.InputBuilder;
import de.augmentia.quad.quarkus.workflow.internal.*;
import de.augmentia.quad.quarkus.workflow.node.NodeConfig;
import de.augmentia.quad.quarkus.workflow.node.NodeExecutor;
import de.augmentia.quad.quarkus.workflow.node.NodeRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Standard executor for agent, messaging-in and messaging-out nodes.
 * Executes the LLM call with an optional timeout and a single retry on empty response,
 * builds the prompt (start envelope / predecessor input) and persists node results.
 */
@ApplicationScoped
public class AgentNodeExecutor implements NodeExecutor {

    private static final Logger log = Logger.getLogger(AgentNodeExecutor.class);

    @Inject ChannelAgentFactory agentFactory;
    @Inject MessagingRouter messagingRouter;
    @Inject
    InputBuilder inputBuilder;
    @Inject
    WorkflowSupport support;
    @Inject
    RunStateWriter runWriter;
    @Inject SessionStore sessionStore;

    @Override
    public Set<String> getTypes() {
        return Set.of("agent", "messaging-in", "messaging-out");
    }

    @Override
    public boolean execute(Map<String, Object> node, List<Map<String, Object>> allNodes,
                           List<Map<String, Object>> edges, WorkflowExecutionContext ctx, NodeRunner runner) {
        String nodeId = NodeConfig.id(node);
        String nodeType = NodeConfig.type(node);
        RunStore.RunState run = ctx.run();
        String initialData = ctx.initialData();
        String fallbackData = ctx.fallbackData();

        String title = node.get("title") != null ? String.valueOf(node.get("title")) : nodeId;
        boolean isStartNode = GraphUtils.predecessorIds(nodeId, edges).isEmpty();
        Map<String, Object> config = NodeConfig.of(node);
        String inputContext = inputBuilder.buildNodeInput(node, edges, ctx.outputs(), ctx.sessionState(), initialData, fallbackData);
        String context = (isStartNode && !"messaging-in".equals(nodeType) && !"messaging-out".equals(nodeType))
            ? inputBuilder.buildStartEnvelope(node, nodeId, title, run, ctx.sessionState(), initialData, config)
            : inputContext;
        if (run != null) runWriter.updateAndPersist(run, nodeId, "running", "", "");
        String output;

        if ("messaging-in".equals(nodeType)) {
            output = executeMessagingIn(node, inputContext, ctx);
        } else if ("messaging-out".equals(nodeType)) {
            String predecessorOutput = inputBuilder.getPredecessorOutput(node, edges, ctx.outputs());
            executeMessagingOut(node, predecessorOutput);
            output = "[sent to messaging channel]";
        } else {
            Agent agent = createConfiguredAgent(agentIdOf(node));
            configureAgent(agent, config);
            long timeoutMs = resolveTimeoutMs(node);

            String prompt2;
            if (config.containsKey("userMessageTemplate") && NodeConfig.isJsonOutputMode(config)) {
                Map<String, String> templateVars = inputBuilder.buildTemplateVars(node, edges, ctx.outputs(), initialData);
                prompt2 = inputBuilder.renderTemplate(String.valueOf(config.get("userMessageTemplate")), templateVars);
            } else {
                prompt2 = inputBuilder.buildNodePrompt(node, context, isStartNode);
            }
            var result = support.runWithTimeout(timeoutMs, () -> agent.execute(prompt2));
            if (result.timedOut()) {
                ctx.putOutput(nodeId, "");
                if (run != null) {
                    // stable step status instead of just a text marker; resume-capable (non-fatal)
                    runWriter.updateAndPersist(run, nodeId, "timed_out", "", "");
                    sessionStore.recordEvent(run.runId, "NODE_TIMED_OUT",
                        "{\"node\":\"" + nodeId + "\",\"status\":\"timed_out\",\"timeoutMs\":" + timeoutMs + "}");
                }
                log.infof("══ WORKFLOW ⏱ [%s] %s %s → timed_out nach %dms ══",
                    run != null ? run.runId : "?", nodeType, nodeId, timeoutMs);
                return true;
            }
            output = result.value();
            if (isBlankResponse(output)) {
                var retry = support.runWithTimeout(timeoutMs, () -> agent.execute(prompt2));
                if (retry.timedOut()) {
                    ctx.putOutput(nodeId, "");
                    if (run != null) {
                        runWriter.updateAndPersist(run, nodeId, "timed_out", "", "");
                        sessionStore.recordEvent(run.runId, "NODE_TIMED_OUT",
                            "{\"node\":\"" + nodeId + "\",\"status\":\"timed_out\",\"timeoutMs\":" + timeoutMs + "}");
                    }
                    log.infof("══ WORKFLOW ⏱ [%s] %s %s → timed_out (retry) nach %dms ══",
                        run != null ? run.runId : "?", nodeType, nodeId, timeoutMs);
                    return true;
                }
                output = retry.value();
            }
            if (isBlankResponse(output)) {
                output = "[node failed: LLM returned no usable response]";
            }
        }

        ctx.putOutput(nodeId, output);
        runWriter.addFinding(ctx.sessionState(), title, output);
        if (run != null) {
            boolean failed = isBlankResponse(output);
            runWriter.updateAndPersist(run, nodeId, failed ? "failed" : "completed", context, output);
            sessionStore.recordEvent(run.runId,
                failed ? "NODE_FAILED" : "NODE_COMPLETED",
                "{\"node\":\"" + nodeId + "\",\"status\":\"" + (failed ? "failed" : "completed") + "\"}");
            log.infof("══ WORKFLOW ✓ [%s] %s %s → %s (%d chars) ══",
                run.runId, nodeType, nodeId, failed ? "FAILED" : "completed",
                output != null ? output.length() : 0);
        }
        return true;
    }

    /**
     * Executes a single (non-specialized) child node and writes its
     * output in {@code ctx.outputs()}. Used by the Async-Executor for async
     * sub-nodes to execute. Returns text output.
     */
    public String runChildNode(Map<String, Object> child, List<Map<String, Object>> allNodes,
                               List<Map<String, Object>> edges, Map<String, String> outputs,
                               WorkflowExecutionContext ctx, String childId) {
        String childType = NodeConfig.type(child);
        if (childType.isBlank()) childType = "agent";
        if ("agent".equals(childType) || "messaging-in".equals(childType) || "messaging-out".equals(childType)) {
            Agent agent = createConfiguredAgent(agentIdOf(child));
            Map<String, Object> config = NodeConfig.of(child);
            configureAgent(agent, config);
            String inputContext = inputBuilder.buildNodeInput(child, edges, outputs, ctx.sessionState(), ctx.initialData(), ctx.initialData());
            long timeoutMs = resolveTimeoutMs(child);
            String output;
            if ("messaging-out".equals(childType)) {
                String predecessorOutput = inputBuilder.getPredecessorOutput(child, edges, outputs);
                executeMessagingOut(child, predecessorOutput);
                output = "[sent to messaging channel]";
            } else {
                String prompt2;
                if (config.containsKey("userMessageTemplate") && NodeConfig.isJsonOutputMode(config)) {
                    Map<String, String> templateVars = inputBuilder.buildTemplateVars(child, edges, outputs, ctx.initialData());
                    prompt2 = inputBuilder.renderTemplate(String.valueOf(config.get("userMessageTemplate")), templateVars);
                } else {
                    prompt2 = inputBuilder.buildNodePrompt(child, inputContext);
                }
                var result = support.runWithTimeout(timeoutMs, () -> agent.execute(prompt2));
                if (result.timedOut()) {
                    output = "[node timed out after " + timeoutMs + "ms]";
                } else {
                    output = result.value();
                    if (isBlankResponse(output)) {
                        var retry = support.runWithTimeout(timeoutMs, () -> agent.execute(prompt2));
                        if (retry.timedOut()) {
                            output = "[node timed out after " + timeoutMs + "ms]";
                        } else {
                            output = retry.value();
                        }
                    }
                    if (isBlankResponse(output)) {
                        output = "[node failed: LLM returned no usable response]";
                    }
                }
            }
            outputs.put(childId, output);
            return output;
        }
        return null;
    }

    private String executeMessagingIn(Map<String, Object> node, String inputContext, WorkflowExecutionContext ctx) {
        Map<String, Object> config = NodeConfig.of(node);
        String topic = String.valueOf(config.get("topic"));
        String tenantId = config.containsKey("tenantId") ? String.valueOf(config.get("tenantId")) : "default";
        long timeoutMs = config.containsKey("timeoutMs")
            ? safeLong(String.valueOf(config.get("timeoutMs")), support.messagingTimeoutMs())
            : support.messagingTimeoutMs();

        String key = tenantId + ":" + topic;
        log.infof("messaging-in: waiting for message on key=%s (timeout=%dms)", key, timeoutMs);

        LinkedBlockingQueue<String> queue = support.pendingQueue(key);

        try {
            String payload = timeoutMs <= 0
                ? queue.take()
                : queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (payload == null) {
                log.warnf("messaging-in: timeout waiting for message on key=%s after %dms", key, timeoutMs);
                throw new RuntimeException("messaging-in timeout: no message received on topic "
                    + topic + " (tenant=" + tenantId + ") within " + timeoutMs + "ms");
            }
            log.infof("messaging-in: received message on key=%s (len=%d)", key, payload.length());
            return payload;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("messaging-in interrupted on key " + key, e);
        } finally {
            support.deregisterQueue(key);
        }
    }

    private void executeMessagingOut(Map<String, Object> node, String payload) {
        Map<String, Object> config = NodeConfig.of(node);
        String channelName = String.valueOf(config.get("channel"));
        String topic = config.containsKey("topic") ? String.valueOf(config.get("topic")) : null;

        log.infof("messaging-out: publishing to channel=%s (topic=%s, len=%d)",
            channelName, topic, payload != null ? payload.length() : 0);

        QuadMessage message = QuadMessage.of("workflow-out", "text", payload != null ? payload : "");
        messagingRouter.sendToSpecificOutbound(channelName, message);
    }

    private Agent createConfiguredAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) agentId = "default-agent";
        return agentFactory.create(agentId);
    }

    /** Timeout-Kette: node.timeoutMs → per-Agent timeoutSeconds → globaler Default. */
    private long resolveTimeoutMs(Map<String, Object> node) {
        long agentTimeoutMs = agentFactory.timeoutMsOrZero(agentIdOf(node));
        long fallback = agentTimeoutMs > 0 ? agentTimeoutMs : support.defaultTimeoutMs();
        return support.nodeTimeoutMs(node, fallback);
    }

    private void configureAgent(Agent agent, Map<String, Object> config) {
        if (config.containsKey("systemPrompt") && config.get("systemPrompt") instanceof String sp && !sp.isBlank()) {
            agent.setSystemPrompt(sp);
        }
        if (config.containsKey("userMessageTemplate") && config.get("userMessageTemplate") instanceof String umt && !umt.isBlank()) {
            agent.setUserMessageTemplate(umt);
        }
        if (config.containsKey("jsonOutput") || config.containsKey("jsonInput")) {
            if (NodeConfig.isJsonOutputMode(config)) agent.setJsonInput(true);
        }
        if (config.containsKey("jsonOutputSchema") && config.get("jsonOutputSchema") instanceof String jsch && !jsch.isBlank()) {
            agent.setStructuredOutputConfig(StructuredOutputConfig.dynamicSchema(jsch));
        }
    }

    private String agentIdOf(Map<String, Object> node) {
        Map<String, Object> config = NodeConfig.of(node);
        if (config.containsKey("agentId")) {
            return String.valueOf(config.get("agentId"));
        }
        return null;
    }

    /** Eine LLM-Antwort gilt as fehlgeschlagen, if null, leer oder nur an Platzhalter/Fehler-Notiz. */
    static boolean isBlankResponse(String s) {
        if (s == null || s.isBlank()) return true;
        String t = s.trim();
        return t.equalsIgnoreCase("No response") || t.equalsIgnoreCase("No response from LLM");
    }

    private long safeLong(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}