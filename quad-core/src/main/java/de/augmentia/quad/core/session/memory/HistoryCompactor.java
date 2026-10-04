package de.augmentia.quad.core.session.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * How a part of the transcript that fell out of the window is summarised.
 *
 * <p><b>Why this is an interface and not fixed code.</b> Before this extension,
 * {@code SessionMemory} cut every text at 200 characters and discarded the rest. That was
 * wrong in three ways:
 *
 * <ol>
 *   <li><b>The cut landed in the middle of the result.</b> A tool result names its value
 *       towards the end. Cutting after 200 characters keeps the request and throws the
 *       value away.</li>
 *   <li><b>It was silent.</b> A truncated transcript looks like a complete one. The
 *       agent does not have its tool result and cannot notice that.</li>
 *   <li><b>It was not replaceable.</b> Anyone wanting a different compaction — by model,
 *       by fixed rules, not at all — could not do it.</li>
 * </ol>
 *
 * <p>A limit still has to exist: a summary that itself grows without bound is just
 * another form of memory leak. The limit now lives in the compaction rather than in the
 * truncation — and the default compaction says when it has cut something.
 *
 * <h2>Introducing your own strategy</h2>
 * <pre>{@code
 * // 1. Custom rule
 * HistoryCompactor mine = (previousSummary, overflow) ->
 *         previousSummary + "\n" + compact(overflow);
 *
 * // 2. Compact via a model
 * HistoryCompactor byModel = new ModelCompactor(chatModel);
 *
 * // 3. Wire it in
 * SessionMemory memory = new SessionMemory(50, byModel);
 * }</pre>
 *
 * <p>Important: the compactor sees {@code ChatMessage}, not the domain's own
 * {@link de.augmentia.quad.core.message.Message}. The agent loop holds LangChain4j
 * messages, so a custom implementation hooks in there without converting the type.
 */
@FunctionalInterface
public interface HistoryCompactor {

    /**
     * Summarises the part that fell out of the window.
     *
     * @param previousSummary  what has been compacted so far, {@code null} the first time.
     *                         An implementation that ignores the previous state loses
     *                         information on every pass.
     * @param overflow         the messages that fell out of the window, in chronological
     *                         order, never empty
     * @return the new summary, never {@code null} and never empty
     */
    String compact(String previousSummary, List<ChatMessage> overflow);

    /** Diagnostics only: named so that it stands out in logs. */
    default String name() {
        return getClass().getSimpleName();
    }
}