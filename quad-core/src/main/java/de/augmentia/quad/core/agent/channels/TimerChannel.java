package de.augmentia.quad.core.agent.channels;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Timer channel that periodically emits notifications.
 * <p>
 * Source: Python {@code src/quad/runtime/channels.py}
 */
public class TimerChannel implements Channel {

    private final QueueChannel delegate = new QueueChannel();
    private final ScheduledFuture<?> scheduledTask;

    public TimerChannel(ScheduledExecutorService scheduler, long intervalMs, String message) {
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler must not be null");
        }
        scheduledTask = scheduler.scheduleAtFixedRate(
                () -> delegate.put("[TIMER] " + message),
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void put(String notification) {
        delegate.put(notification);
    }

    @Override
    public List<String> pollAll() {
        return delegate.pollAll();
    }

    public void shutdown() {
        scheduledTask.cancel(true);
    }
}
