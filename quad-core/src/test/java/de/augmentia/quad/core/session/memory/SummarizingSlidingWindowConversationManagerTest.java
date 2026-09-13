package de.augmentia.quad.core.session.memory;

import de.augmentia.quad.core.message.Message;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SummarizingSlidingWindowConversationManagerTest {

    private ChatModel mockSummarizer(String summaryText) {
        ChatModel model = mock(ChatModel.class);
        ChatResponse response = ChatResponse.builder()
            .aiMessage(AiMessage.from(summaryText))
            .build();
        when(model.chat(any(ChatRequest.class))).thenReturn(response);
        return model;
    }

    @Test
    void invalidMaxTokensThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new SummarizingSlidingWindowConversationManager(mockSummarizer("x"), 0, 2));
    }

    @Test
    void invalidKeepLastUserMessagesThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new SummarizingSlidingWindowConversationManager(mockSummarizer("x"), 1000, 0));
    }

    @Test
    void returnsAllIfUnderTokenLimit() {
        var mgr = new SummarizingSlidingWindowConversationManager(mockSummarizer("x"), 100000, 2);
        List<Message> msgs = List.of(
            Message.system("sys"),
            Message.user("u1"),
            Message.assistant("a1")
        );
        assertEquals(3, mgr.prune(msgs).size());
    }

    @Test
    void doesNotSummarizeIfTooFewNonSystemMessages() {
        var summarizer = mockSummarizer("summary");
        var mgr = new SummarizingSlidingWindowConversationManager(summarizer, 1, 2);

        List<Message> msgs = List.of(
            Message.system("sys"),
            Message.user("u1"),
            Message.assistant("a1")
        );
        List<Message> pruned = mgr.prune(msgs);
        assertEquals(3, pruned.size());
        verify(summarizer, never()).chat(any(ChatRequest.class));
    }

    @Test
    void summarizesWhenOverTokenLimitAndEnoughMessages() {
        var summarizer = mockSummarizer("This is a summary of the conversation.");
        var mgr = new SummarizingSlidingWindowConversationManager(summarizer, 10, 2);

        List<Message> msgs = new ArrayList<>();
        msgs.add(Message.system("Be helpful"));
        for (int i = 0; i < 15; i++) {
            msgs.add(Message.user("question " + i));
            msgs.add(Message.assistant("answer " + i));
        }

        List<Message> pruned = mgr.prune(msgs);
        assertTrue(pruned.size() < msgs.size());

        // Should contain a summary system message
        boolean hasSummary = pruned.stream().anyMatch(m ->
            m.isSystem() && m.content() != null && m.content().contains("CONVERSATION SUMMARY"));
        assertTrue(hasSummary, "Pruned list should contain a CONVERSATION SUMMARY");

        // Should still contain the original system message
        boolean hasOriginalSys = pruned.stream().anyMatch(m ->
            m.isSystem() && m.content() != null && m.content().equals("Be helpful"));
        assertTrue(hasOriginalSys, "Original system message should be preserved");

        verify(summarizer).chat(any(ChatRequest.class));
    }

    @Test
    void emptyListReturnsEmpty() {
        var mgr = new SummarizingSlidingWindowConversationManager(mockSummarizer("x"), 100, 2);
        assertTrue(mgr.prune(List.of()).isEmpty());
    }

    @Test
    void singleMessageReturnsAsIs() {
        var mgr = new SummarizingSlidingWindowConversationManager(mockSummarizer("x"), 1, 2);
        List<Message> pruned = mgr.prune(List.of(Message.user("only")));
        assertEquals(1, pruned.size());
    }
}
