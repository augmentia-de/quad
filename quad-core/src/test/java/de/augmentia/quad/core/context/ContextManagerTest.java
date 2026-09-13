package de.augmentia.quad.core.context;

import de.augmentia.quad.core.capability.context.ContextManager;
import dev.langchain4j.data.message.SystemMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextManagerTest {

    @Test
    void shouldRenderStaticBlock() {
        ContextManager manager = new ContextManager();
        manager.set("skills", "java, quarkus");

        SystemMessage message = manager.renderSystemMessage("base", null);

        assertThat(message.text())
                .contains("base")
                .contains("[skills]: java, quarkus");
    }

    @Test
    void shouldEvaluateDynamicBlockPerRender() {
        ContextManager manager = new ContextManager();
        AtomicInteger counter = new AtomicInteger(0);
        manager.addBlock(new ContextManager.DynamicBlock("turn", () -> String.valueOf(counter.incrementAndGet())));

        manager.renderSystemMessage(null, null);
        String second = manager.renderSystemMessage(null, null).text();

        assertThat(second).contains("[turn]: 2");
    }

    @Test
    void shouldIncludeProtectedBlockInRenderButHideFromUserBlocks() {
        ContextManager manager = new ContextManager();
        manager.addBlock(new ContextManager.ProtectedBlock("system_prompt", "framework"));
        manager.set("skill", "golang");

        assertThat(manager.userBlocks()).extracting(b -> b.key()).containsExactly("skill");
        assertThat(manager.allBlocks()).hasSize(2);

        String text = manager.renderSystemMessage(null, null).text();
        assertThat(text).contains("[PROTECTED:system_prompt]: framework");
        assertThat(text).contains("[skill]: golang");
    }

    @Test
    void shouldRefuseToOverwriteOrRemoveProtectedBlock() {
        ContextManager manager = new ContextManager();
        manager.addBlock(new ContextManager.ProtectedBlock("system_prompt", "framework"));

        assertThatThrownBy(() -> manager.set("system_prompt", "new"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("protected");
        assertThatThrownBy(() -> manager.remove("system_prompt"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("protected");
    }

    @Test
    void shouldRenderPrefixBlocksBeforeSuffixBlocks() {
        ContextManager manager = new ContextManager();
        manager.addBlock(new ContextManager.StaticBlock("suffix", "later", false));
        manager.addBlock(new ContextManager.StaticBlock("prefix", "first", true));

        String text = manager.renderSystemMessage("base", null).text();

        assertThat(text.indexOf("[prefix]: first"))
                .isLessThan(text.indexOf("[suffix]: later"));
    }

    @Test
    void shouldHonorBlockOrder() {
        ContextManager manager = new ContextManager();
        manager.addBlock(new ContextManager.StaticBlock("a", "A"));
        manager.addBlock(new ContextManager.StaticBlock("b", "B"));
        manager.addBlock(new ContextManager.StaticBlock("c", "C"));

        String text = manager.renderSystemMessage("base", List.of("c", "a")).text();

        assertThat(text.indexOf("[c]: C")).isLessThan(text.indexOf("[a]: A"));
        assertThat(text).doesNotContain("[b]: B");
    }
}
