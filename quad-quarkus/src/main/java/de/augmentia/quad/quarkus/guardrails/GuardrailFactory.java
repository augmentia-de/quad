package de.augmentia.quad.quarkus.guardrails;

import de.augmentia.quad.core.guardrails.Guardrail;
import de.augmentia.quad.core.guardrails.GuardrailResult;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Translates guardrail names from the agent configuration (UI)
 * into actual core {@link Guardrail} implementations.
 */
public final class GuardrailFactory {

    private static final Logger log = Logger.getLogger(GuardrailFactory.class);

    private static final Pattern EMAIL = Pattern.compile(
        "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);

    private static final Pattern SQL_INJECTION = Pattern.compile(
        "(?i)(drop\\s+table|union\\s+select|select\\s+\\*\\s+from\\s+information_schema|--\\s*$|;\\s*delete\\s+from)");

    private static final Pattern PROMPT_INJECTION = Pattern.compile(
        "(?i)(ignore\\s+(all\\s+)?(previous|above|earlier)\\s+instructions|disregard\\s+system\\s+instructions|reveal\\s+your\\s+system\\s+prompt)");

    private GuardrailFactory() {}

    public static Guardrail fromName(String name) {
        if (name == null || name.isBlank()) return null;
        return switch (name.strip().toLowerCase()) {
            case "pii", "pii-filter", "email", "emails" -> (messages, context) ->
                EMAIL.matcher(messages.toString()).find()
                    ? GuardrailResult.block("PII detected (email address)")
                    : GuardrailResult.ok();
            case "sql-injection", "sqli" -> (messages, context) ->
                SQL_INJECTION.matcher(messages.toString()).find()
                    ? GuardrailResult.block("Potential SQL injection detected")
                    : GuardrailResult.ok();
            case "prompt-injection", "jailbreak" -> (messages, context) ->
                PROMPT_INJECTION.matcher(messages.toString()).find()
                    ? GuardrailResult.block("Prompt injection attempt detected")
                    : GuardrailResult.ok();
            default -> {
                log.warnf("Unknown guardrail '%s' — ignoring", name);
                yield null;
            }
        };
    }

    /** Maps string names to guardrails; unknown names are skipped */
    public static List<Guardrail> fromNames(String[] names) {
        var result = new ArrayList<Guardrail>();
        if (names == null) return result;
        for (String name : names) {
            Guardrail g = fromName(name);
            if (g != null) result.add(g);
        }
        return result;
    }
}