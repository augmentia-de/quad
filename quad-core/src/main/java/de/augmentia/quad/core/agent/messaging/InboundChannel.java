package de.augmentia.quad.core.agent.messaging;

public interface InboundChannel {

    String name();

    default String topic() { return null; }

    void start(MessageHandler handler);

    void stop();

    boolean isRunning();

    @FunctionalInterface
    interface MessageHandler {
        void handle(QuadMessage message);
    }
}
