package de.augmentia.quad.core.session.memory;

import de.augmentia.quad.core.message.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A custom {@link ConversationManager} has to be usable.
 *
 * <p>The interface used to be {@code sealed} and admissible only for the two shipped
 * implementations. A third strategy was therefore formally impossible — the "pluggable"
 * claim held only for the two existing variants. This test fails should that ever be
 * walled up again, which is why it is not a self-purpose.
 */
@DisplayName("ConversationManager — custom implementation")
class ConversationManagerErweiterbarTest {

    private static Message message(String text) {
        return Message.assistant(text);
    }

    @Test
    @DisplayName("a custom implementation can be passed in and is used")
    void customImplementationIsUsed() {
        List<Integer> calls = new ArrayList<>();

        // A third rule: always keep the last three messages.
        ConversationManager custom = messages -> {
            calls.add(messages.size());
            return messages.subList(Math.max(0, messages.size() - 3), messages.size());
        };

        var input = List.of(message("1"), message("2"), message("3"),
                message("4"), message("5"));
        var result = custom.prune(input);

        assertThat(calls).as("the custom rule was actually consulted").containsExactly(5);
        assertThat(result).hasSize(3);
        assertThat(result.get(0).content()).isEqualTo("3");
    }

    @Test
    @DisplayName("the shipped managers remain usable")
    void shippedManagersRemainUsable() {
        var input = List.of(message("1"), message("2"), message("3"), message("4"));

        assertThat(new SlidingWindowConversationManager(2).prune(input)).hasSize(2);
        assertThat(new SummarizingSlidingWindowConversationManager(null, 2, 2).prune(input))
                .as("the existing compaction does not depend on sealedness")
                .isNotNull();
    }
}