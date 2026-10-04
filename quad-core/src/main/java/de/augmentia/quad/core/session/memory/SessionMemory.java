package de.augmentia.quad.core.session.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Session memory: holds the chat history per {@code AgentSessionState}.
 *
 * <p>When the window ({@link #maxMessages}) overflows, the oldest messages are
 * <b>not discarded but compacted</b> — nothing is lost, it is merely no longer verbatim.
 *
 * <h2>When compaction happens, and who does it</h2>
 *
 * <p>Compaction happens exactly when {@link #record} is called and the history exceeds
 * the window. That has a consequence which is easy to miss:
 *
 * <p>{@code record} is called by {@code AgentRuntime} <b>after</b> the ReAct run, once
 * for the finished transcript. <em>During</em> a run this class sees nothing — and what
 * it does not see, it does not compact. Compaction here is therefore responsible for the
 * <em>cross-invocation</em> history: many calls back and forth within one session.
 *
 * <p>What limits things <em>within</em> a run is something else:
 * {@link ConversationManager} in {@code AgentRuntime.limitContext()} — it runs before
 * every model call and is the reason unlimited rounds are possible at all. The two solve
 * different problems and should not be confused:
 *
 * <table border="1">
 *   <caption>What limits what</caption>
 *   <tr><th></th><th>within one run</th><th>across invocations</th></tr>
 *   <tr><td>triggered by</td><td>every round</td><td>{@code record}</td></tr>
 *   <tr><td>mechanism</td><td>{@link ConversationManager#prune}</td>
 *       <td>{@link HistoryCompactor#compact}</td></tr>
 *   <tr><td>operates on</td><td>the live message field</td>
 *       <td>the stored history</td></tr>
 *   <tr><td>loses</td><td>wording, not content</td><td>wording, not content</td></tr>
 * </table>
 *
 * <h2>Custom compaction</h2>
 * <pre>{@code
 * var memory = new SessionMemory(50, new ModelCompactor(chatModel));
 * var memory = new SessionMemory(50, new StructuralCompactor(400));
 * }</pre>
 *
 * <p>Without an explicit argument, {@link StructuralCompactor} applies with a budget that
 * fits the window.
 */
public class SessionMemory {

    private static final Logger log = Logger.getLogger(SessionMemory.class.getName());

    /** Character budget per message unless stated otherwise. */
    private static final int STANDARD_BUDGET = 1200;

    private final int maxMessages;
    private final HistoryCompactor compactor;
    private final List<ChatMessage> history = new ArrayList<>();
    private String summary;

    public SessionMemory() {
        this(50);
    }

    public SessionMemory(int maxMessages) {
        this(maxMessages, new StructuralCompactor(STANDARD_BUDGET));
    }

    public SessionMemory(int maxMessages, HistoryCompactor compactor) {
        if (maxMessages <= 0) throw new IllegalArgumentException("maxMessages must be > 0");
        if (compactor == null) throw new IllegalArgumentException("compactor must not be null");
        this.maxMessages = maxMessages;
        this.compactor = compactor;
    }

    public int maxMessages() { return maxMessages; }

    public HistoryCompactor compactor() { return compactor; }

    /** Appends messages; the overflow is compacted, not discarded. */
    public synchronized void record(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        history.addAll(messages);
        int overflow = history.size() - maxMessages;
        if (overflow <= 0) return;

        var oldest = new ArrayList<>(history.subList(0, overflow));
        history.subList(0, overflow).clear();

        try {
            summary = compactor.compact(summary, oldest);
        } catch (RuntimeException e) {
            // A compactor that fails must not discard the transcript. The messages stay
            // in the window, the overflow is recorded as text — visibly worse, but not
            // lost.
            log.warning("Compaction with " + compactor.name() + " failed ("
                    + e.getMessage() + ") — carrying the overflow over uncompacted");
            summary = (summary == null ? "" : summary + "\n")
                    + "Carried over uncompacted (" + oldest.size() + " messages):\n"
                    + oldest + "\n";
        }
    }

    public String summary() { return summary; }

    public boolean hasSummary() { return summary != null && !summary.isBlank(); }

    public synchronized List<ChatMessage> history() { return List.copyOf(history); }

    public synchronized void clear() {
        history.clear();
        summary = null;
    }
}