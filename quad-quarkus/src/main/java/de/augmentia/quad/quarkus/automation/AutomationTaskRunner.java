package de.augmentia.quad.quarkus.automation;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.session.memory.SessionMemory;
import de.augmentia.quad.core.workflow.schedule.ScheduledTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Executes one automation task by delegating to {@code ChannelAgentFactory}.
 */
@ApplicationScoped
public class AutomationTaskRunner implements AutomationService.AutomationTaskRunner {

    private static final Logger log = Logger.getLogger(AutomationTaskRunner.class);

    @Inject
    de.augmentia.quad.quarkus.messaging.ChannelAgentFactory agentFactory;

    @ConfigProperty(name = "quad.memory.max-messages", defaultValue = "50")
    int memoryMaxMessages;

    @Override
    public TaskRun execute(ScheduledTask task, String trigger) {
        TaskRun run = new TaskRun(task.getId(), trigger);
        try {
            String agentId = task.getAgent();
            if (agentId == null || agentId.isBlank()) agentId = "cowork";
            Agent agent = agentFactory.create(agentId);
            AgentSessionState state = new AgentSessionState();
            state.setMemory(new SessionMemory(memoryMaxMessages));
            CurrentSession.setCurrent(state);
            try {
                String answer = agent.execute(scheduledPrompt(task), state);
                run.setResultText(answer != null ? answer : "");
                run.setStatus(answer != null ? TaskRun.STATUS_OK : TaskRun.STATUS_ERROR);
            } finally {
                CurrentSession.setCurrent(null);
            }
        } catch (Exception ex) {
            log.errorf(ex, "Automation task %s failed", task.getId());
            run.setStatus(TaskRun.STATUS_ERROR);
            run.setError(ex.getMessage());
        } finally {
            run.setFinishedAt(java.time.Instant.now());
        }
        return run;
    }

    private String scheduledPrompt(ScheduledTask task) {
        return "Scheduled run — " + task.getTitle() + "\n\n"
            + "This automation is due now: carry out the task below immediately.\n\n"
            + task.getInstructions();
    }
}
