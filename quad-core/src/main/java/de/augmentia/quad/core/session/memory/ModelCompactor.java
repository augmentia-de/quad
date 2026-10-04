package de.augmentia.quad.core.session.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

/**
 * Compaction via a model.
 *
 * <p>The route for transcripts where eliding things does harm. {@link StructuralCompactor}
 * keeps the head and the tail of a message — enough for tool results, whose value sits at
 * the end. For a transcript in which a decision was made in the middle and later messages
 * build on it, only a model that ties the thread together will do.
 *
 * <p>The call is two-stage: the older summary first, then the overflow, because the older
 * one is the result of the previous compaction and would otherwise be lost. The summary is
 * carried along — without it, compaction degenerates over many passes into a chain in
 * which only the last section survives.
 *
 * <p><b>Cost.</b> Every call is a model call. With a window of 50 and an overflow of 10
 * messages that is one per 10 rounds — not negligible for unlimited runs. That is what
 * {@link #maxCompactions} is for: after that, compaction switches off and the transcript
 * keeps growing rather than being quietly trimmed.
 */
public final class ModelCompactor implements HistoryCompactor {

    private static final String INSTRUCTION = """
            You summarise part of an agent conversation.

            Keep: the task, decisions made with their reason, tool results with their values,
            and what is still open.
            Do not lose: numeric values, file paths, names, error messages.
            Label parts that are missing explicitly instead of passing over them.

            Write only the summary.""";

    private final ChatModel model;
    private final int maxCompactions;
    private int compactions;

    /** Unlimited compactions. */
    public ModelCompactor(ChatModel model) {
        this(model, 0);
    }

    /**
     * @param maxCompactions 0 = unlimited. Otherwise compaction stops after this many
     *                       passes and the transcript keeps growing. That is a deliberate
     *                       choice: a compactor that switches itself off is more honest
     *                       than one that silently loses content.
     */
    public ModelCompactor(ChatModel model, int maxCompactions) {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        if (maxCompactions < 0) {
            throw new IllegalArgumentException("maxCompactions must be >= 0");
        }
        this.model = model;
        this.maxCompactions = maxCompactions;
    }

    public int compactions() {
        return compactions;
    }

    @Override
    public String compact(String previousSummary, List<ChatMessage> overflow) {
        if (overflow == null || overflow.isEmpty()) {
            return previousSummary == null ? "" : previousSummary;
        }
        if (maxCompactions > 0 && compactions >= maxCompactions) {
            throw new IllegalStateException(
                    "Compaction budget exhausted (" + maxCompactions
                    + ") — the transcript keeps growing untrimmed from here");
        }
        compactions++;

        var sb = new StringBuilder(INSTRUCTION);
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("\n\nState so far:\n").append(previousSummary);
        }
        sb.append("\n\nTo summarise:\n");
        for (ChatMessage m : overflow) {
            sb.append('[').append(m.type()).append("] ")
                    .append(StructuralCompactor.textOf(m)).append("\n");
        }

        var response = model.chat(ChatRequest.builder()
                .messages(UserMessage.from(sb.toString()))
                .build());
        var text = response.aiMessage() == null ? null : response.aiMessage().text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("The compaction model returned nothing");
        }
        return text.trim();
    }

    @Override
    public String name() {
        return "ModelCompactor(" + compactions + "/"
                + (maxCompactions == 0 ? "unlimited" : maxCompactions) + ")";
    }
}