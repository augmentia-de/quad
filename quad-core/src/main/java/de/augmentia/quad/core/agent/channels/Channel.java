package de.augmentia.quad.core.agent.channels;

import java.util.List;

/**
 * Async notification channel for agent communication.
 * <p>
 * Source: Python {@code src/quad/runtime/channels.py}
 */
public interface Channel {

    void put(String notification);

    List<String> pollAll();
}
