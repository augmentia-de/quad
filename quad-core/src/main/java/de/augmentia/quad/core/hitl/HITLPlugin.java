package de.augmentia.quad.core.hitl;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.guardrails.ApprovalResult;
import de.augmentia.quad.core.hitl.checkpoint.Checkpoint;
import de.augmentia.quad.core.hitl.checkpoint.CheckpointService;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import de.augmentia.quad.core.hook.plugin.Plugin;

import java.util.List;

/**
 * Plugin that enforces human-in-the-loop approval before tool execution.
 */
public class HITLPlugin implements Plugin {

    private final HITLProvider provider;
    private final HITLAuthority authority;
    private final List<String> reviewActions;
    private CheckpointService checkpointService;
    private Agent agent;

    /**
     * Creates a HITL plugin with no review-action filter.
     */
    public HITLPlugin(HITLProvider provider, HITLAuthority authority) {
        this(provider, authority, List.of());
    }

    /**
     * Creates a HITL plugin scoped to specific tool actions that require review.
     */
    public HITLPlugin(HITLProvider provider, HITLAuthority authority, List<String> reviewActions) {
        this.provider = provider;
        this.authority = authority;
        this.reviewActions = reviewActions;
    }

    /**
     * Creates a checkpoint-based HITL plugin without a provider.
     */
    public HITLPlugin(CheckpointService checkpointService) {
        this.provider = null;
        this.authority = HITLAuthority.CONFIRM;
        this.reviewActions = List.of();
        this.checkpointService = checkpointService;
    }

    @Override
    public String name() {
        return "hitl";
    }

    @Override
    public void initAgent(Agent agent) {
        this.agent = agent;
    }

    @Override
    public HookResult beforeToolCall(HookContexts.BeforeToolCallContext ctx) {
        if (checkpointService != null) {
            return handleCheckpoint(ctx);
        }
        if (authority == HITLAuthority.AUTO) {
            return new HookResult.Continue();
        }
        if (authority == HITLAuthority.DENY) {
            return new HookResult.Cancel("HITL: tool '" + ctx.toolName() + "' denied by policy");
        }
        if (!reviewActions.isEmpty() && !reviewActions.contains(ctx.toolName())) {
            return new HookResult.Continue();
        }
        var approval = provider.requestApproval(ctx.toolName(), ctx.arguments().toString());
        if (approval.approved()) {
            return new HookResult.Continue();
        }
        return new HookResult.Cancel(approval.feedback() != null ? approval.feedback() : "Rejected by user");
    }

    private HookResult handleCheckpoint(HookContexts.BeforeToolCallContext ctx) {
        if (!checkpointService.requiresApproval(ctx.toolName())) {
            return new HookResult.Continue();
        }
        var args = ctx.arguments() != null ? ctx.arguments().toString() : "{}";
        var cp = checkpointService.createCheckpoint(ctx.sessionId(), ctx.toolName(), args);
        if (agent != null) {
            agent.pauseExecution();
        }
        try {
            var resolved = checkpointService.await(cp);
            if (resolved.status() == Checkpoint.Status.APPROVED) {
                if (agent != null) {
                    agent.approve();
                }
                return new HookResult.Continue();
            } else {
                var reason = resolved.feedback() != null ? resolved.feedback() : "Rejected via HITL checkpoint";
                if (agent != null) {
                    agent.reject(reason);
                }
                return new HookResult.Cancel(reason);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HookResult.Cancel("Interrupted while waiting for HITL approval");
        }
    }

    public HITLProvider provider() {
        return provider;
    }

    public HITLAuthority authority() {
        return authority;
    }

    public List<String> reviewActions() {
        return reviewActions;
    }

    public CheckpointService checkpointService() {
        return checkpointService;
    }

    public void setCheckpointService(CheckpointService checkpointService) {
        this.checkpointService = checkpointService;
    }

    public Agent agent() {
        return agent;
    }

    /**
     * Returns a simple console-based HITL provider for interactive approval.
     */
    public static HITLProvider consoleProvider() {
        return (action, context) -> {
            System.out.print("Tool '" + action + "' with args: " + context + " \u2014 execute? [y/N] ");
            var input = new java.util.Scanner(System.in).nextLine();
            return input.equalsIgnoreCase("y")
                ? ApprovalResult.approved(action)
                : ApprovalResult.denied(action, "Rejected by console user");
        };
    }
}
