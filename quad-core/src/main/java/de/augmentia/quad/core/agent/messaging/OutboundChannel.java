package de.augmentia.quad.core.agent.messaging;

public interface OutboundChannel {

    String name();

    void send(QuadMessage message);

    boolean isAvailable();
}
