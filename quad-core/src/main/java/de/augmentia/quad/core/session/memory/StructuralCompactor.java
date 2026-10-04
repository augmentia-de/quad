package de.augmentia.quad.core.session.memory;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.logging.Logger;

/**
 * Compaction without a model: keeps head and tail, marks the gap.
 *
 * <p>The predecessor truncation cut at 200 characters and appended {@code "..."}. That
 * left exactly the wrong thing for a tool result: the call, not the value.
 *
 * <p>This compaction keeps both — the head, which names the task, and the tail, which
 * carries the result — and states in between how much is missing. The difference is not
 * cosmetic: the model can see from the marker that something is absent and ask again or
 * re-fetch it. With a silent cut it cannot.
 *
 * <p>The budget is an upper bound, not a cut inside a line. A single message that does
 * not fit even with head and tail is skipped as a whole and logged, rather than being
 * fragmented.
 */
public final class StructuralCompactor implements HistoryCompactor {

    private static final Logger log = Logger.getLogger(StructuralCompactor.class.getName());

    private static final String ELIDED = "… [%d characters elided] …";

    private final int charsPerMessage;
    private final int minCharsPerMessage;

    /**
     * @param charsPerMessage  budget per message. Shorter texts are left untouched.
     * @param minCharsPerMessage  below this length nothing is trimmed at all — a marker on
     *                            three lines is louder than the text it replaces.
     */
    public StructuralCompactor(int charsPerMessage) {
        this(charsPerMessage, Math.min(120, charsPerMessage / 2));
    }

    public StructuralCompactor(int charsPerMessage, int minCharsPerMessage) {
        if (charsPerMessage < 32) {
            throw new IllegalArgumentException(
                    "charsPerMessage must be >= 32, got: " + charsPerMessage);
        }
        if (minCharsPerMessage < 16) {
            throw new IllegalArgumentException(
                    "minCharsPerMessage must be >= 16, got: " + minCharsPerMessage);
        }
        this.charsPerMessage = charsPerMessage;
        this.minCharsPerMessage = minCharsPerMessage;
    }

    /** The budget that fits {@code SessionMemory}'s window. */
    public static StructuralCompactor withBudget(int charsPerMessage) {
        return new StructuralCompactor(charsPerMessage);
    }

    @Override
    public String compact(String previousSummary, List<ChatMessage> overflow) {
        var sb = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append(previousSummary).append("\n");
        }
        sb.append("Summarised so far:\n");

        var elided = 0;
        for (ChatMessage m : overflow) {
            var text = textOf(m);
            if (text.length() > charsPerMessage) {
                // Too long for the budget: do not fragment. Half a message is worse than
                // none — it claims to be complete.
                elided += text.length();
                sb.append('[').append(role(m)).append("] ")
                        .append("message too long for the budget (")
                        .append(text.length()).append(" characters), not carried over")
                        .append('\n');
                continue;
            }
            if (text.length() <= minCharsPerMessage) {
                sb.append('[').append(role(m)).append("] ").append(text).append('\n');
                continue;
            }
            int head = charsPerMessage / 3;
            int tail = charsPerMessage - head;
            sb.append('[').append(role(m)).append("] ")
                    .append(text, 0, head)
                    .append('\n').append(String.format(ELIDED, text.length() - head - tail))
                    .append('\n').append(text, text.length() - tail, text.length())
                    .append('\n');
        }

        if (elided > 0) {
            log.warning("Compaction did not carry over " + elided
                    + " characters — raise the budget or use ModelCompactor");
        }
        return sb.toString();
    }

    @Override
    public String name() {
        return "StructuralCompactor(" + charsPerMessage + ")";
    }

    /**
     * The text content of a message — not {@code toString()}.
     *
     * <p>{@code ChatMessage.toString()} yields the object wrapper:
     * {@code UserMessage { name = null, contents = [TextContent { text = "..." }], ...}}.
     * A 4000-character message thereby becomes 4084 characters of rubbish in which the
     * actual text is buried — and the cut would hit the wrapper, not the content.
     *
     * <p>For image and audio parts a type marker stands in. The transcript then says
     * "there was an image" without inventing content or losing it silently.
     */
    static String textOf(ChatMessage m) {
        return switch (m.type()) {
            case USER -> contentsOf(((dev.langchain4j.data.message.UserMessage) m).contents());
            case AI -> ((dev.langchain4j.data.message.AiMessage) m).text();
            case SYSTEM -> ((dev.langchain4j.data.message.SystemMessage) m).text();
            case TOOL_EXECUTION_RESULT ->
                    ((dev.langchain4j.data.message.ToolExecutionResultMessage) m).text();
            default -> m.toString();
        };
    }

    private static String contentsOf(List<dev.langchain4j.data.message.Content> contents) {
        var sb = new StringBuilder();
        for (var c : contents) {
            if (!sb.isEmpty()) sb.append('\n');
            if (c instanceof dev.langchain4j.data.message.TextContent t) {
                sb.append(t.text());
            } else {
                sb.append('[').append(c.type()).append(']');
            }
        }
        return sb.toString();
    }

    private static String role(ChatMessage m) {
        return switch (m.type()) {
            case SYSTEM -> "System";
            case USER -> "User";
            case AI -> "Assistant";
            case TOOL_EXECUTION_RESULT -> "Tool";
            default -> "?";
        };
    }
}