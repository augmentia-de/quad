package de.augmentia.quad.core.agent.channels;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages multiple channels and injects their notifications into the
 * message list before it is sent to the LLM.
 * <p>
 * Source: Python {@code src/quad/runtime/channels.py}
 */
public class ChannelManager {

    private final List<Channel> channels = new CopyOnWriteArrayList<>();

    public void addChannel(Channel channel) {
        if (channel != null) {
            channels.add(channel);
        }
    }

    public void removeChannel(Channel channel) {
        channels.remove(channel);
    }

    /**
     * Polls all channels and appends pending notifications as
     * SystemMessage to the provided list.
     *
     * @return number of injected notifications
     */
    public int injectNotifications(List<ChatMessage> messages) {
        int injected = 0;
        for (Channel channel : channels) {
            for (String notification : channel.pollAll()) {
                messages.add(SystemMessage.from("[ASYNC NOTIFICATION] " + notification));
                injected++;
            }
        }
        return injected;
    }
}
