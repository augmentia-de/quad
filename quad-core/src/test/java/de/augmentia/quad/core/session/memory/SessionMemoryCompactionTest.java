package de.augmentia.quad.core.session.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Compaction instead of truncation.
 *
 * <p>The old rule was: text beyond 200 characters gone, {@code "..."} appended. That kept
 * the call for a tool result and discarded the value — and the transcript looked complete.
 * These tests pin down that neither holds any more.
 */
@DisplayName("SessionMemory — compaction")
class SessionMemoryCompactionTest {

    private static final int VERY_LONG = 4000;
    /** Between minChars (120) and the budget (600): the range in which trimming happens. */
    private static final int MEDIUM = 400;

    private static String text(int length) {
        var sb = new StringBuilder();
        while (sb.length() < length) {
            sb.append("reading 4711 some more text ");
        }
        return sb.substring(0, length);
    }

    @Nested
    @DisplayName("StructuralCompactor")
    class Structural {

        @Test
        @DisplayName("keeps the head and the tail of a long text")
        void keepsHeadAndTail() {
            var t = text(MEDIUM);
            var result = new StructuralCompactor(600).compact(null,
                    List.of(UserMessage.from(t)));

            assertThat(result)
                    .as("the head stays — that is where the task is")
                    .contains(t.substring(0, 40));
            assertThat(result)
                    .as("the tail stays — for a tool result that is where the value is")
                    .contains(t.substring(MEDIUM - 40));
        }

        @Test
        @DisplayName("states how much is missing")
        void missingIsStated() {
            var t = text(MEDIUM);
            var result = new StructuralCompactor(600).compact(null,
                    List.of(UserMessage.from(t)));

            assertThat(result)
                    .as("without a hint at the gap the loss is not recognisable")
                    .contains("characters elided");
        }

        @Test
        @DisplayName("never touches a short text")
        void shortTextIsUntouched() {
            var t = "The result is 42.";
            var result = new StructuralCompactor(600).compact(null,
                    List.of(UserMessage.from(t)));

            assertThat(result).contains(t).doesNotContain("elided");
        }

        @Test
        @DisplayName("keeps earlier summaries")
        void keepsEarlierSummary() {
            var result = new StructuralCompactor(600).compact("Up to here: step 1 open.",
                    List.of(AiMessage.from("step 2 done")));

            assertThat(result)
                    .as("without carrying it forward every pass only keeps the last section")
                    .contains("Up to here: step 1 open.");
        }

        @Test
        @DisplayName("skips a single message that is too long rather than fragmenting it")
        void tooLongMessageIsNotFragmented() {
            // Budget smaller than the message: fragmenting produces two half facts. Better
            // none at all — and logged.
            var t = text(VERY_LONG);
            var result = new StructuralCompactor(40).compact(null,
                    List.of(UserMessage.from(t)));

            assertThat(result)
                    .as("half a message claims to be complete")
                    .doesNotContain(t.substring(10, 400))
                    .contains("not carried over");
        }
    }

    @Nested
    @DisplayName("ModelCompactor")
    class WithModel {

        private ChatModel repliesWith(String text) {
            var model = mock(ChatModel.class);
            when(model.chat(any(ChatRequest.class)))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
            return model;
        }

        @Test
        @DisplayName("asks the model and takes its answer")
        void usesTheModel() {
            var result = new ModelCompactor(repliesWith("Summarised: value 42"))
                    .compact(null, List.of(UserMessage.from("lots of text")));

            assertThat(result).isEqualTo("Summarised: value 42");
        }

        @Test
        @DisplayName("throws when the budget is exhausted")
        void exhaustedBudgetThrows() {
            var compactor = new ModelCompactor(repliesWith("x"), 2);
            compactor.compact(null, List.of(UserMessage.from("a")));
            compactor.compact("previous", List.of(UserMessage.from("a")));

            assertThatThrownBy(() -> compactor.compact("previous", List.of(UserMessage.from("a"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exhausted");
        }

        @Test
        @DisplayName("counts its compactions")
        void countsCompactions() {
            var compactor = new ModelCompactor(repliesWith("x"));
            assertThat(compactor.compactions()).isZero();
            compactor.compact(null, List.of(UserMessage.from("a")));
            assertThat(compactor.compactions()).isOne();
        }
    }

    @Nested
    @DisplayName("wiring into SessionMemory")
    class Wiring {

        @Test
        @DisplayName("the overflow is compacted, not discarded")
        void overflowIsCompacted() {
            var memory = new SessionMemory(2);

            memory.record(List.of(
                    UserMessage.from("task: evaluate the reading"),
                    AiMessage.from("I will call the tool"),
                    ToolExecutionResultMessage.from("t1", "reading", "result: 42.0"),
                    AiMessage.from("done")));

            assertThat(memory.history())
                    .as("only the last two stay in the window")
                    .hasSize(2);
            assertThat(memory.summary())
                    .as("the task is compacted, not lost")
                    .contains("evaluate the reading");

            // Now the tool result leaves the window too.
            memory.record(List.of(AiMessage.from("and now?")));

            assertThat(memory.history()).hasSize(2);
            assertThat(memory.summary())
                    .as("the result moves into the summary as well")
                    .contains("result: 42.0");
            assertThat(memory.summary())
                    .as("and the earlier part does not get lost in the process")
                    .contains("evaluate the reading");
        }

        @Test
        @DisplayName("a custom compactor can be plugged in")
        void customCompactorCanBePluggedIn() {
            var compactions = new ArrayList<Integer>();
            HistoryCompactor custom = (previous, overflow) -> {
                compactions.add(overflow.size());
                return "CUSTOM SUMMARY";
            };

            var memory = new SessionMemory(2, custom);
            memory.record(List.of(UserMessage.from("a")));
            memory.record(List.of(UserMessage.from("b")));
            memory.record(List.of(UserMessage.from("c")));

            assertThat(compactions).hasSize(1);
            assertThat(memory.summary()).isEqualTo("CUSTOM SUMMARY");
        }

        @Test
        @DisplayName("if compaction fails the overflow is not lost")
        void failingCompactionLosesNothing() {
            HistoryCompactor broken = (previous, overflow) -> {
                throw new IllegalStateException("model unreachable");
            };

            var memory = new SessionMemory(1, broken);
            memory.record(List.of(UserMessage.from("important task")));
            memory.record(List.of(UserMessage.from("something else")));

            assertThat(memory.summary())
                    .as("visibly worse rather than silently gone")
                    .contains("uncompacted")
                    .contains("important task");
        }

        @Test
        @DisplayName("the default is StructuralCompactor with a window-sized budget")
        void defaultCompaction() {
            assertThat(new SessionMemory().compactor())
                    .as("without an explicit argument, compaction is structural")
                    .isInstanceOf(StructuralCompactor.class);
            assertThat(new SessionMemory().compactor().name())
                    .contains("StructuralCompactor");
        }
    }
}