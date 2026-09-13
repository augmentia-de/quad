package de.augmentia.quad.core.agent.channels;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Queue-based channel for asynchronous messages.
 * <p>
 * Source: Python {@code src/quad/runtime/channels.py}
 */
public class QueueChannel implements Channel {

    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    @Override
    public void put(String notification) {
        queue.add(notification);
    }

    @Override
    public List<String> pollAll() {
        List<String> list = new ArrayList<>();
        String item;
        while ((item = queue.poll()) != null) {
            list.add(item);
        }
        return list;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}
