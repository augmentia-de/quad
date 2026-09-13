package de.augmentia.quad.core.gdpr;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import io.opentelemetry.api.trace.Span;

import java.util.regex.Pattern;

/**
 * PII redaction hook (LangChain4j ChatModelListener).
 *
 * <p>LangChain4j listener contexts are IMMUTABLE: the live request going to the model
 * must not be modified (it would corrupt the LLM input). Redaction is therefore
 * applied to every audited copy — trace attributes, audit records,
 * streamed UI events — at the boundaries where data is persisted or exported.</p>
 */
public class PiiRedactionHook implements ChatModelListener {

    private final Pattern[] piiPatterns = {
        Pattern.compile("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
        Pattern.compile("\\b\\d{16}\\b")
    };

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        String scrubbed = redactPII(ctx.chatRequest().messages().toString());
        Span.current().setAttribute("llm.request.scrubbed", scrubbed);
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        String scrubbed = redactPII(ctx.chatResponse().aiMessage().text());
        Span.current().setAttribute("llm.response.scrubbed", scrubbed);
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Span.current().setAttribute("llm.error.scrubbed",
            redactPII(ctx.error().toString()));
    }

    public String redactPII(String text) {
        if (text == null) return null;
        for (Pattern p : piiPatterns) {
            text = p.matcher(text).replaceAll("[PII_REDACTED]");
        }
        return text;
    }
}
