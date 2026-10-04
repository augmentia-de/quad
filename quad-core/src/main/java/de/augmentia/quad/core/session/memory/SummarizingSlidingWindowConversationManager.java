package de.augmentia.quad.core.session.memory;

import de.augmentia.quad.core.message.Message;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SummarizingSlidingWindowConversationManager(
    ChatModel summarizer,
    int maxTokens,
    int keepLastUserMessages
) implements ConversationManager {

    private static final int TOKEN_ESTIMATE_DIVISOR = 4;
    private static final double SYSTEM_AGGREGATE_THRESHOLD = 0.30;
    private static final int SYSTEM_AGGREGATE_MIN = 2;

    private static final String INSTRUCTION = """
        Summarize the following conversation concisely.
        Retain important facts, decisions, and outcomes.
        If tool results appear, summarize their core content.
        Answer only with the summary, without introduction or commentary.""";

    private static final String AGGREGATE_INSTRUCTION = """
        Combine the following system messages into a single, coherent
        system instruction. Retain all important rules and constraints.
        Answer only with the combined message.""";

    public SummarizingSlidingWindowConversationManager {
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1, got: " + maxTokens);
        }
        if (keepLastUserMessages < 1) {
            throw new IllegalArgumentException("keepLastUserMessages must be >= 1, got: " + keepLastUserMessages);
        }
    }

    @Override
    public List<Message> prune(List<Message> messages) {
        int totalTokens = estimateTokens(messages);
        if (totalTokens <= maxTokens) {
            return messages;
        }

        if (messages.size() < 2) {
            return messages;
        }

        List<Message> systemMsgs = new ArrayList<>();
        List<Message> nonSystemMsgs = new ArrayList<>();
        for (var msg : messages) {
            if (msg.isSystem()) {
                systemMsgs.add(msg);
            } else {
                nonSystemMsgs.add(msg);
            }
        }

        if (nonSystemMsgs.size() <= 10) {
            return messages;
        }

        List<Message> toKeep = selectLastUserMessagesBlock(nonSystemMsgs);
        if (toKeep == nonSystemMsgs) {
            return messages;
        }

        int splitIndex = nonSystemMsgs.size() - toKeep.size();
        List<Message> toSummarize = nonSystemMsgs.subList(0, splitIndex);

        if (toKeep.size() * 2 >= toSummarize.size()) {
            return messages;
        }

        if (estimateTokens(toSummarize) <= estimateTokens(toKeep) * 2) {
            return messages;
        }

        List<Message> result = new ArrayList<>();

        if (!systemMsgs.isEmpty()) {
            result.addAll(maybeAggregateSystem(systemMsgs, messages.size()));
        }

        String summary = generateSummary(toSummarize);
        result.add(Message.system(
            UUID.randomUUID().toString(),
            Instant.now(),
            "[CONVERSATION SUMMARY]\n" + summary,
            Map.of()
        ));
        result.addAll(toKeep);

        return result;
    }

    /**
     * Determines which block at the end remains untouched.
     *
     * <p><b>Anchoring on user messages, with a fallback to a position boundary.</b> In a chat
     * transcript a user message is a clean turn boundary: what follows belongs to that turn. In
     * a tool loop that is different — there are <em>no</em> further user messages after the
     * task, only AI answers and tool results. Without the fallback the loop would never find a
     * boundary, {@code cutIndex} would stay at the end and nothing would <em>ever</em> be
     * compacted. That would be the silent failure mode: the budget set and without effect.
     *
     * <p>The fallback keeps the last messages and widens the cut towards the front until it
     * sits at the start of an AI answer. Otherwise a tool result would remain without its
     * matching request — the model reads that as incomplete and hallucinates the request to go
     * with it.
     */
    private List<Message> selectLastUserMessagesBlock(List<Message> nonSystemMsgs) {
        int userCount = 0;
        int cutIndex = nonSystemMsgs.size();
        for (int i = nonSystemMsgs.size() - 1; i >= 0; i--) {
            if (nonSystemMsgs.get(i).isUser()) {
                userCount++;
                if (userCount == keepLastUserMessages) {
                    cutIndex = i;
                    break;
                }
            }
        }

        if (userCount >= keepLastUserMessages) {
            return nonSystemMsgs.subList(cutIndex, nonSystemMsgs.size());
        }

        // Fallback: no (or too few) user messages — tool loop.
        int keep = Math.min(nonSystemMsgs.size(), Math.max(keepLastUserMessages * 3, 6));
        int start = nonSystemMsgs.size() - keep;

        // Reach back towards the front to the start of an AI answer so that no tool result
        // lies in the block without its request. The way is bounded: in a tool loop EVERY AI
        // answer is a request with results, a boundary on "assistant without tool call" never
        // matches and one would run all the way to the start.
        int limit = Math.max(0, start - 3);
        while (start > limit && !nonSystemMsgs.get(start).isAssistant()) {
            start--;
        }

        return nonSystemMsgs.subList(start, nonSystemMsgs.size());
    }

    private List<Message> maybeAggregateSystem(List<Message> systemMsgs, int totalSize) {
        if (systemMsgs.size() < SYSTEM_AGGREGATE_MIN) {
            return systemMsgs;
        }
        if ((double) systemMsgs.size() / totalSize < SYSTEM_AGGREGATE_THRESHOLD) {
            return systemMsgs;
        }

        var sb = new StringBuilder();
        sb.append(AGGREGATE_INSTRUCTION).append("\n\n");
        for (int i = 0; i < systemMsgs.size(); i++) {
            sb.append(i + 1).append(". ").append(systemMsgs.get(i).content()).append("\n");
        }

        var request = ChatRequest.builder()
            .messages(UserMessage.from(sb.toString()))
            .build();
        ChatResponse response = summarizer.chat(request);
        String combined = response.aiMessage().text();

        return List.of(Message.system(
            UUID.randomUUID().toString(),
            Instant.now(),
            combined,
            Map.of()
        ));
    }

    private String generateSummary(List<Message> messages) {
        var sb = new StringBuilder();
        sb.append(INSTRUCTION).append("\n\n");
        for (var msg : messages) {
            String role;
            if (msg.isUser()) {
                role = "User";
            } else if (msg.isAssistant() && msg.hasToolExecutionRequests()) {
                role = "Assistant (Tool-Call)";
            } else if (msg.isAssistant()) {
                role = "Assistant";
            } else if (msg.isSystem()) {
                role = "System";
            } else if (msg.isToolResult()) {
                role = "Tool (" + msg.toolName() + ")";
            } else {
                role = "Unknown";
            }
            sb.append(role).append(": ").append(msg.content() != null ? msg.content() : "").append("\n");
        }

        var request = ChatRequest.builder()
            .messages(UserMessage.from(sb.toString()))
            .build();
        ChatResponse response = summarizer.chat(request);
        return response.aiMessage().text();
    }

    private int estimateTokens(List<Message> messages) {
        return messages.stream()
            .mapToInt(m -> {
                String c = m.content();
                return (c != null ? c.length() : 0) / TOKEN_ESTIMATE_DIVISOR + 4;
            })
            .sum();
    }
}
