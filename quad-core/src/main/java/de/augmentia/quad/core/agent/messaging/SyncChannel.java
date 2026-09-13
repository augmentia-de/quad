package de.augmentia.quad.core.agent.messaging;

public interface SyncChannel {

    String name();

    String process(QuadMessage message);
}
