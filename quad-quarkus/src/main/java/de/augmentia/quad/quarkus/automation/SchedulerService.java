package de.augmentia.quad.quarkus.automation;

import de.augmentia.quad.core.workflow.schedule.ScheduledTask;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The scheduler loop — {@code @Scheduled every="10s"} tick that finds due tasks
 * and fires them via a pluggable {@link Runner}. Skip-on-overlap semantics.
 */
@ApplicationScoped
public class SchedulerService {

    private static final Logger log = Logger.getLogger(SchedulerService.class);

    @FunctionalInterface
    public interface Runner {
        TaskRun run(ScheduledTask task, String trigger);
    }

    @Inject
    AutomationStore store;
    @Inject
    AutomationTaskRunner taskRunner;

    @ConfigProperty(name = "quad.automation.enabled", defaultValue = "false")
    boolean enabled;

    private volatile Runner runner;
    private final Set<String> runningIds = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean catchupDone = false;

    @Scheduled(every = "10s")
    void scheduledTick() {
        if (!enabled) return;
        String trigger = catchupDone ? TaskRun.TRIGGER_SCHEDULE : TaskRun.TRIGGER_CATCHUP;
        catchupDone = true;
        tick(trigger);
    }

    /** Inspect due tasks and fire them. */
    public void tick(String trigger) {
        if (!enabled) return;
        for (ScheduledTask task : store.due(Instant.now())) {
            runTask(task, trigger);
        }
    }

    public void runTask(ScheduledTask task, String trigger) {
        if (!runningIds.add(task.getId())) {
            log.infof("Skipping %s — previous run still going", task.getId());
            return;
        }
        Runner active = runner != null ? runner : (t, tr) -> taskRunner.execute(t, tr);
        executor.submit(() -> {
            try { active.run(task, trigger); }
            catch (Exception ex) {
                log.errorf(ex, "Task %s run failed", task.getId());
                TaskRun failed = new TaskRun(task.getId(), trigger);
                failed.setStatus(TaskRun.STATUS_ERROR);
                failed.setError(String.valueOf(ex.getMessage()));
                failed.setFinishedAt(Instant.now());
                store.addRun(failed);
            } finally { runningIds.remove(task.getId()); }
        });
    }

    public boolean isRunning(String taskId) { return runningIds.contains(taskId); }
    public int runningCount() { return runningIds.size(); }
    public boolean isEnabled() { return enabled; }

    /** Test hook: override the run implementation (null restores default). */
    public void setRunner(Runner runner) { this.runner = runner; }

    /** Test hook: reset catchup flag so next tick reports "catchup". */
    public void resetCatchupForTest() { catchupDone = false; }
}
