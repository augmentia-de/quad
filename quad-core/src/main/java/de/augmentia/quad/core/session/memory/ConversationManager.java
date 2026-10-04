package de.augmentia.quad.core.session.memory;

import de.augmentia.quad.core.message.Message;

import java.util.List;

/**
 * Bounds the message history <b>during an agent run</b>.
 *
 * <p>Called by {@code AgentRuntime.limitContext()} before every model call, that is,
 * before every round of the ReAct loop. This is the reason an unlimited loop is possible
 * at all: the history does not grow without bound, it is pruned before every call.
 *
 * <p><b>Open, not {@code sealed}.</b> The interface used to be {@code sealed} and
 * inheritable only by the two shipped implementations. A third strategy — a custom rule,
 * a different cost model, different behaviour on tool loops — was therefore impossible
 * short of going around the barrier via {@link HistoryCompactor}. The barrier stood in
 * the way of the "pluggable" claim and has been removed.
 *
 * <p><b>Two rules for custom implementations.</b>
 * <ol>
 *   <li><b>Do not mutate.</b> The passed-in list belongs to the caller. A custom
 *       implementation must not modify it but return a new one.</li>
 *   <li><b>The system context stays.</b> The leading system message belongs to the task,
 *       not to the transcript. Prune it and the agent works against an instruction it no
 *       longer has — and that failure is not visible in the transcript.</li>
 * </ol>
 *
 * <p>Which overlap exists is a deliberate decision, not an accident:
 * {@link SlidingWindowConversationManager} keeps the last n messages,
 * {@link SummarizingSlidingWindowConversationManager} summarises older parts. The same
 * question for the cross-invocation transcript is answered by {@link HistoryCompactor}.
 */
public interface ConversationManager {

    /**
     * Returns the pruned message list.
     *
     * @param messages  the current transcript, to be treated as immutable
     * @return the transcript actually presented to the model
     */
    List<Message> prune(List<Message> messages);
}