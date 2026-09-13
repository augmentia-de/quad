package de.augmentia.quad.core.channels;

import de.augmentia.quad.core.agent.channels.ChannelManager;
import de.augmentia.quad.core.agent.channels.QueueChannel;
import de.augmentia.quad.core.agent.channels.TimerChannel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelTest {

    @Test
    void queueChannelShouldStoreAndDrain() {
        QueueChannel channel = new QueueChannel();
        channel.put("one");
        channel.put("two");

        assertThat(channel.pollAll()).containsExactly("one", "two");
        assertThat(channel.pollAll()).isEmpty();
    }

    @Test
    void channelManagerShouldInjectNotificationsIntoMessages() {
        QueueChannel channel = new QueueChannel();
        channel.put("ping");
        ChannelManager manager = new ChannelManager();
        manager.addChannel(channel);

        List<ChatMessage> messages = new ArrayList<>();
        int injected = manager.injectNotifications(messages);

        assertThat(injected).isEqualTo(1);
        assertThat(messages).hasSize(1);
        assertThat(((SystemMessage) messages.get(0)).text())
                .isEqualTo("[ASYNC NOTIFICATION] ping");
    }

    @Test
    void timerChannelShouldEmitPeriodically() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            TimerChannel timer = new TimerChannel(scheduler, 50, "tick");
            try {
                Thread.sleep(130);
            } finally {
                timer.shutdown();
            }
            List<String> notifications = timer.pollAll();
            assertThat(notifications).hasSizeGreaterThanOrEqualTo(1);
            assertThat(notifications.get(0)).contains("[TIMER] tick");
        } finally {
            scheduler.shutdownNow();
        }
    }
}
