package de.augmentia.quad.core.gdpr;

import de.augmentia.quad.core.hook.pipeline.AgentHook;
import de.augmentia.quad.core.hook.pipeline.HookContexts;
import de.augmentia.quad.core.hook.pipeline.HookResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Anonymizes PII in the messages sent to the model before the call.
 * Redaction is applied to the mutable message list of the before-model-call context.
 */
public class PiiAnonymizerHook implements AgentHook {

    public enum MaskType { EMAIL, PHONE_NUMBER, NAME_DE, CREDIT_CARD, ADDRESS }
    public enum BlockAction { REDACT, THROW, MOCK }

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN =
        Pattern.compile("(?:\\+49|0)[\\s-]?[1-9][0-9\\.\\-\\s/]{6,20}");
    private static final Pattern CREDIT_CARD_PATTERN =
        Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");
    private static final Pattern NAME_PATTERN_DE =
        Pattern.compile("\\b(?:Herr|Frau|Dr\\.?|Prof\\.?)\\s+[A-Z][a-zäöüß]+(?:\\s+[A-Z][a-zäöüß]+)*\\b");
    private static final Pattern ADDRESS_PATTERN_DE =
        Pattern.compile("\\b[A-Za-zäöüß]+(?:\\.)?\\s+\\d+\\s*,\\s*\\d{5}\\s+[A-Za-zäöüß]+\\b");

    private final Set<MaskType> maskTypes;
    private final BlockAction blockAction;
    private final String replacement;

    public PiiAnonymizerHook(Set<MaskType> maskTypes, BlockAction blockAction, String replacement) {
        this.maskTypes = maskTypes;
        this.blockAction = blockAction;
        this.replacement = replacement;
    }

    @Override
    public String name() {
        return "gdpr-pii-anonymizer";
    }

    @Override
    public HookResult beforeModelCall(HookContexts.BeforeModelCallContext ctx) {
        var messages = ctx.messages();
        var modified = false;

        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            var content = content(msg);
            if (content == null) continue;

            var sanitized = maskPii(content);
            if (!sanitized.equals(content)) {
                modified = true;
                messages.set(i, createMaskedMessage(msg, sanitized));
            }
        }

        if (!modified) {
            return new HookResult.Continue();
        }

        if (blockAction == BlockAction.THROW) {
            return new HookResult.Cancel(
                "Prompt contains personal data: request blocked");
        }

        return new HookResult.Continue();
    }

    private static String content(ChatMessage msg) {
        if (msg instanceof UserMessage um) return um.singleText();
        if (msg instanceof SystemMessage sm) return sm.text();
        if (msg instanceof AiMessage am) return am.text();
        if (msg instanceof ToolExecutionResultMessage tr) return tr.text();
        return null;
    }

    private String maskPii(String text) {
        var result = text;
        if (maskTypes.contains(MaskType.EMAIL)) {
            result = EMAIL_PATTERN.matcher(result).replaceAll(replacement);
        }
        if (maskTypes.contains(MaskType.PHONE_NUMBER)) {
            result = PHONE_PATTERN.matcher(result).replaceAll(replacement);
        }
        if (maskTypes.contains(MaskType.CREDIT_CARD)) {
            result = CREDIT_CARD_PATTERN.matcher(result).replaceAll(replacement);
        }
        if (maskTypes.contains(MaskType.NAME_DE)) {
            result = NAME_PATTERN_DE.matcher(result).replaceAll(replacement);
        }
        if (maskTypes.contains(MaskType.ADDRESS)) {
            result = ADDRESS_PATTERN_DE.matcher(result).replaceAll(replacement);
        }
        return result;
    }

    private static ChatMessage createMaskedMessage(ChatMessage original, String sanitized) {
        if (original instanceof UserMessage) {
            return UserMessage.from(sanitized);
        }
        if (original instanceof SystemMessage) {
            return new SystemMessage(sanitized);
        }
        if (original instanceof AiMessage am) {
            var tools = am.toolExecutionRequests();
            return tools != null && !tools.isEmpty()
                ? AiMessage.from(sanitized, tools)
                : new AiMessage(sanitized);
        }
        if (original instanceof ToolExecutionResultMessage tr) {
            var request = ToolExecutionRequest.builder()
                .id(tr.id())
                .name(tr.toolName())
                .build();
            return ToolExecutionResultMessage.from(request, sanitized);
        }
        return original;
    }
}
