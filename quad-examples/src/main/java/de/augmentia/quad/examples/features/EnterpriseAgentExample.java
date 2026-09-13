package de.augmentia.quad.examples.features;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.agent.AgentBuilder;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.config.ModelFactory;
import de.augmentia.quad.core.gdpr.PiiAnonymizerHook;
import de.augmentia.quad.core.guardrails.Guardrail;
import de.augmentia.quad.core.guardrails.GuardrailPlugin;
import de.augmentia.quad.core.guardrails.GuardrailResult;
import de.augmentia.quad.core.hitl.HITLPlugin;
import de.augmentia.quad.core.hitl.checkpoint.CheckpointService;
import de.augmentia.quad.core.hitl.checkpoint.ConsoleChannel;
import de.augmentia.quad.core.hitl.checkpoint.InMemoryCheckpointStore;
import de.augmentia.quad.core.hook.pipeline.AgentHook;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import de.augmentia.quad.core.hook.plugin.Plugin;
import de.augmentia.quad.core.hook.plugin.PluginRegistry;
import de.augmentia.quad.core.resilience.Retry;
import de.augmentia.quad.core.resilience.RetryConfig;
import de.augmentia.quad.core.observability.LoggingHook;
import de.augmentia.quad.core.agent.runtime.AgentEventPublisher;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.WorkspaceResolver;
import de.augmentia.quad.core.tool.SubAgentTool;

import java.util.List;
import java.util.Set;

/**
 * Comprehensive example: EnterpriseAgent with all core features
 *
 * Bundles all features in a single agent implementation:
 * <ol>
 *   <li>ReAct flow &amp; sub-agents</li>
 *   <li>Hook pipeline</li>
 *   <li>Guardrails</li>
 *   <li>Plugins</li>
 *   <li>Telemetry &amp; Observability</li>
 *   <li>Resilience &amp; Fault Tolerance</li>
 *   <li>Human-in-the-Loop &amp; Checkpoints</li>
 *   <li>GDPR &amp; PII masking</li>
 *   <li>Context &amp; Workspace</li>
 * </ol>
 */

// 1. Sub-agent definition for ReAct / SubAgent feature
class WorkerSubAgent extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Executes a protected task.")
    public String doWork(@Param("task") String task) {
        // 6. Resilience feature in tool
        try {
            return Retry.run(() -> "Result for: " + task, RetryConfig.DEFAULT);
        } catch (Exception e) {
            return "Fehler: " + e.getMessage();
        }
    }
}

// Main agent with all bundled features
public class EnterpriseAgentExample extends Agent {

    @Override
    protected AgentSessionState newSessionState() {
        return new AgentSessionState();
    }

    @Tool(description = "Critical system command requiring HITL.")
    public String executeBash(@Param("script") String script) {
        return "Command executed: " + script;
    }

    public static void main(String[] args) {
        // 9. Context & workspace init
        AgentSessionState sessionState = new AgentSessionState();
        sessionState.setTenantId("enterprise-tenant");
        String workspace = WorkspaceResolver.resolve(sessionState.getSessionId());
        System.out.println("Workspace: " + workspace);

        // 5. Telemetry & observability
        AgentEventPublisher events = new AgentEventPublisher();
        events.addEventListener(new LoggingHook()::onEvent);

        // Create sub-agent
        Agent workerSubAgent = AgentBuilder.create(WorkerSubAgent.class)
            .withLlmFromEnv()
            .build();

        // Configure main agent
        EnterpriseAgentExample agent = new EnterpriseAgentExample();
        agent.setLlm(ModelFactory.createOpenAiFromEnv());
        agent.setEventPublisher(events);

        // 1. Register sub-agent
        agent.getToolRegistry().register("worker", new SubAgentTool(workerSubAgent, "worker"));

        // 2. Hook pipeline
        agent.addHook(new AgentHook() {
            @Override
            public String name() {
                return "CustomLifecycleHook";
            }

            @Override
            public HookResult beforeAgent(HookContexts.BeforeAgentContext ctx) {
                System.out.println("[HOOK] Agent startet: " + ctx.prompt());
                return HookResult.Continue.INSTANCE;
            }
        });

        // 3. Guardrails
        Guardrail inputGuardrail = (messages, context) ->
            messages.toString().contains("FORBIDDEN")
                ? GuardrailResult.block("Verbotener Begriff")
                : GuardrailResult.ok();
        agent.addHook(new GuardrailPlugin(List.of(inputGuardrail), List.of()));

        // 7. Human-in-the-Loop (HITL) & checkpoints
        CheckpointService checkpointService = new CheckpointService(
            new InMemoryCheckpointStore(), "executeBash", 120_000
        );
        checkpointService.registerChannel(new ConsoleChannel());
        agent.addHook(new HITLPlugin(checkpointService));

        // 8. GDPR & PII masking
        agent.addHook(new PiiAnonymizerHook(
            Set.of(PiiAnonymizerHook.MaskType.EMAIL),
            PiiAnonymizerHook.BlockAction.REDACT,
            "[REDACTED]"
        ));

        // 4. Plugin system initialization
        Plugin customPlugin = new Plugin() {
            @Override
            public String name() {
                return "EnterprisePlugin";
            }

            @Override
            public void initAgent(Agent a) {
                System.out.println("[PLUGIN] EnterprisePlugin initialized for " + a.getClass().getSimpleName());
            }
        };
        new PluginRegistry(List.of(customPlugin)).initialize(agent);

        // Execute via ReAct loop
        String response = agent.executeReAct(
            "Run executeBash with 'ls' and contact worker regarding task X.",
            sessionState
        );

        System.out.println("Finale Antwort: " + response);
    }
}
