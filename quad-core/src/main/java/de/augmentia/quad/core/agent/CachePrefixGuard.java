package de.augmentia.quad.core.agent;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;

/**
 * Guard for the shared prefix-cache ("Block A") invariant: every agent sharing a
 * {@code cachePrefix} must serialize an identical native tool list so that the
 * whole shared head of consecutive requests stays byte-identical.
 *
 * <p>Verified on langchain4j-open-ai 1.15.0: {@code ChatCompletionRequest} declares
 * {@code messages} before {@code tools}, so a tool divergence does not break the
 * cached system prefix on OpenAI — but it ends the cache span at the first
 * divergent spec. Identical specs (same name, description, parameter schema, and
 * same registration order) keep the tools themselves cacheable, which is the point
 * of Block A.</p>
 */
public final class CachePrefixGuard {

    private CachePrefixGuard() {
    }

    /**
     * Order-sensitive, byte-comparable fingerprint of the agent's native tool list.
     */
    public static String toolFingerprint(Agent agent) {
        return toolFingerprint(agent.toolSpecifications());
    }

    /**
     * Order-sensitive fingerprint; one line per tool spec, in registered order.
     */
    public static String toolFingerprint(List<ToolSpecification> specs) {
        StringBuilder sb = new StringBuilder();
        for (ToolSpecification spec : specs) {
            sb.append(spec.toJson()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Enforces the shared-prefix invariant: throws if the two cache-prefix agents
     * expose different native tool lists.
     */
    public static void requireIdenticalTools(Agent a, Agent b) {
        String fa = toolFingerprint(a);
        String fb = toolFingerprint(b);
        if (!fa.equals(fb)) {
            throw new IllegalStateException(
                    "Cache-prefix agents expose different native tool specifications; "
                            + "the shared prefix would not stay cacheable.\n"
                            + "Agent A tools:\n" + fa
                            + "Agent B tools:\n" + fb);
        }
    }
}