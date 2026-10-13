package de.augmentia.quad.core.guardrails;

import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Guardrails must be enforced by the plugin's before/after-model-call hooks. */
class GuardrailPluginTest {

    private static Guardrail blockOn(String needle) {
        return (messages, context) -> String.valueOf(messages).contains(needle)
                ? GuardrailResult.block("matched " + needle)
                : GuardrailResult.ok();
    }

    private static HookContexts.BeforeModelCallContext beforeCtx(String userMessage) {
        return new HookContexts.BeforeModelCallContext("s", new StringBuilder(),
                List.of(UserMessage.from(userMessage)), List.of(), List.of());
    }

    @Test
    void blocksInputWhenGuardrailMatches() {
        GuardrailPlugin plugin = new GuardrailPlugin(List.of(blockOn("secret")), List.of());

        HookResult result = plugin.beforeModelCall(beforeCtx("tell me the secret"));

        assertInstanceOf(HookResult.Cancel.class, result);
        // default BlockAction is FALLBACK
        assertEquals("I cannot process this request.", ((HookResult.Cancel) result).reason());
    }

    @Test
    void passesInputWhenNoGuardrailMatches() {
        GuardrailPlugin plugin = new GuardrailPlugin(List.of(blockOn("secret")), List.of());

        assertInstanceOf(HookResult.Continue.class, plugin.beforeModelCall(beforeCtx("hello")));
    }

    @Test
    void blocksOutputWhenGuardrailMatches() {
        GuardrailPlugin plugin = new GuardrailPlugin(List.of(), List.of(blockOn("secret")));
        var ctx = new HookContexts.AfterModelCallContext("s", "the secret is 42", 1, 1);

        HookResult result = plugin.afterModelCall(ctx, "the secret is 42");

        assertInstanceOf(HookResult.Cancel.class, result);
    }

    @Test
    void throwActionSurfacesGuardrailReason() {
        GuardrailPlugin plugin = new GuardrailPlugin(List.of(blockOn("secret")), List.of(),
                BlockAction.THROW, "fallback");

        HookResult result = plugin.beforeModelCall(beforeCtx("secret"));

        assertInstanceOf(HookResult.Cancel.class, result);
        assertTrue(((HookResult.Cancel) result).reason().contains("matched secret"));
    }

    @Test
    void withoutGuardrailsIsNoop() {
        GuardrailPlugin plugin = new GuardrailPlugin(List.of(), List.of());

        assertInstanceOf(HookResult.Continue.class, plugin.beforeModelCall(beforeCtx("anything")));
        assertInstanceOf(HookResult.Continue.class,
                plugin.afterModelCall(new HookContexts.AfterModelCallContext("s", "x", 0, 0), "x"));
    }
}
