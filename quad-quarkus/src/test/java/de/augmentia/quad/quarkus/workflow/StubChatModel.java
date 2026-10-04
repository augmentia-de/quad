package de.augmentia.quad.quarkus.workflow;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Deterministic {@link ChatModel} stub for tests.
 *
 * <p>Answers from a queue of canned replies, so a multi-turn flow (for example the
 * retry loop in {@code WorkflowGeneratorService.callOrchestrator}) can be driven
 * deterministically: queue a malformed reply first and a valid one second, and the
 * test asserts that the retry recovered.
 *
 * <p>When the queue is exhausted the last reply is repeated — a test that does not
 * care about the exact number of calls stays stable instead of throwing.
 *
 * <p>Every request is recorded in {@link #requests()} so a test can assert on what
 * the agent actually sent (system prompt, retry hint).
 */
class StubChatModel implements ChatModel {

    private final Deque<String> replies = new ArrayDeque<>();
    private final List<ChatRequest> requests = new ArrayList<>();
    private String last = "";

    /** Queues one reply per expected call, in order. */
    StubChatModel(String... queued) {
        for (String r : queued) {
            replies.add(r);
        }
    }

    /** Convenience for the common single-answer case. */
    static StubChatModel replying(String text) {
        return new StubChatModel(text);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        requests.add(request);
        if (!replies.isEmpty()) {
            last = replies.poll();
        }
        return ChatResponse.builder().aiMessage(AiMessage.from(last)).build();
    }

    /** All requests seen so far, in order. */
    List<ChatRequest> requests() {
        return requests;
    }

    int callCount() {
        return requests.size();
    }

    /**
     * Text of the first system message of the given request, or {@code null}.
     * langchain4j 1.x keeps {@code text()} on the message subtypes, not on the
     * {@code ChatMessage} interface.
     */
    static String systemText(ChatRequest request) {
        if (request == null) return null;
        for (ChatMessage m : request.messages()) {
            if (m instanceof SystemMessage sm) return sm.text();
        }
        return null;
    }

    /** Text of the first user message of the given request, or {@code null}. */
    static String userText(ChatRequest request) {
        if (request == null) return null;
        for (ChatMessage m : request.messages()) {
            if (m instanceof UserMessage um) return um.singleText();
        }
        return null;
    }

    /** System prompt of the first request. */
    String firstSystemPrompt() {
        return requests.isEmpty() ? null : systemText(requests.get(0));
    }

    /** User message of the first request. */
    String firstUserMessage() {
        return requests.isEmpty() ? null : userText(requests.get(0));
    }
}