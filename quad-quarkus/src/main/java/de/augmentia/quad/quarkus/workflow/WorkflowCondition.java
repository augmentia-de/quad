package de.augmentia.quad.quarkus.workflow;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tiny condition evaluator shared by {@code loop} and {@code conditional} workflow
 * nodes. Expressions are intentionally simple and deterministic:
 *
 * <ul>
 *   <li>{@code always} / {@code true}  → always true</li>
 *   <li>{@code never} / {@code false} → always false</li>
 *   <li>{@code contains:needle} → true when the scope text contains needle (case-insensitive)</li>
 *   <li>{@code notcontains:needle} → negation of contains</li>
 *   <li>{@code equals:text} → true when the scope text (trimmed) equals text</li>
 *   <li>{@code matches:regex} → true when the scope text matches the regex</li>
 *   <li>{@code iter==N}, {@code iter!=N}, {@code iter&lt;N}, {@code iter&lt;=N}, {@code iter&gt;N}, {@code iter&gt;=N} → iteration counts</li>
 *   <li>{@code empty} / anything else → false</li>
 * </ul>
 */
public final class WorkflowCondition {

    private WorkflowCondition() {
    }

    public static boolean matches(String expr, String scopeText, int iteration) {
        String text = scopeText != null ? scopeText : "";
        if (expr == null || expr.isBlank()) {
            return false;
        }
        String e = expr.trim();
        String lower = e.toLowerCase(Locale.ROOT);
        if ("always".equals(lower) || "true".equals(lower)) return true;
        if ("never".equals(lower) || "false".equals(lower) || "empty".equals(lower)) return false;

        if (lower.startsWith("contains:")) {
            String needle = e.substring("contains:".length()).trim();
            return !needle.isEmpty() && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
        }
        if (lower.startsWith("notcontains:")) {
            String needle = e.substring("notcontains:".length()).trim();
            return needle.isEmpty() || !text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
        }
        if (lower.startsWith("equals:")) {
            return text.trim().equals(e.substring("equals:".length()).trim());
        }
        if (lower.startsWith("matches:")) {
            String regex = e.substring("matches:".length()).trim();
            try {
                return Pattern.compile(regex).matcher(text).find();
            } catch (Exception ex) {
                return false;
            }
        }
        if (lower.startsWith("iter")) {
            return evalIterExpression(e, iteration);
        }
        return false;
    }

    private static boolean evalIterExpression(String expr, int iteration) {
        String body = expr.substring("iter".length()).trim();
        if (body.startsWith("==")) return iteration == parseInt(body.substring(2));
        if (body.startsWith("!=")) return iteration != parseInt(body.substring(2));
        if (body.startsWith(">=")) return iteration >= parseInt(body.substring(2));
        if (body.startsWith("<=")) return iteration <= parseInt(body.substring(2));
        if (body.startsWith(">")) return iteration > parseInt(body.substring(1));
        if (body.startsWith("<")) return iteration < parseInt(body.substring(1));
        return false;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}