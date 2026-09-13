package de.augmentia.quad.quarkus.resilience;

import de.augmentia.quad.core.agent.runtime.ToolExecutor;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.tool.QuadToolRegistry;
import de.augmentia.quad.core.tool.ToolMethod;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MicroProfile Fault Tolerance wrapper around {@link ToolExecutor}.
 *
 * <h3>Responsibilities (layered, not duplicated):</h3>
 * <ul>
 *   <li><b>{@link ToolExecutor} (Core)</b> — Tool call lifecycle:
 *       idempotency, saga compensation, hooks, event publishing, OTel spans.</li>
 *   <li><b>{@code ResilientToolExecutor} (this class)</b> — Transport resilience:
 *       retry/timeout/circuit breaker/fallback at the MP Fault Tolerance level.</li>
 *   <li><b>{@code RetryingChatModel} (Core)</b> — LLM level: retry with backoff.</li>
 * </ul>
 * MP-FT properties: {@code quad.resilience.*}.
 */
@ApplicationScoped
public class ResilientToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResilientToolExecutor.class);

    @Inject
    QuadToolRegistry registry;

    @Inject
    ToolExecutor toolExecutor;

    @Retry(maxRetries = 3, delay = 1000)
    @Timeout(30_000)
    @CircuitBreaker(
        requestVolumeThreshold = 10,
        failureRatio = 0.5f,
        delay = 60_000
    )
    @Fallback(fallbackMethod = "defaultResult")
    public String executeTool(String toolName, String argsJson, AgentSessionState state) {
        ToolMethod tool = registry.get(toolName);
        if (tool == null) {
            return "Tool not found: " + toolName;
        }
        return toolExecutor.execute(tool, argsJson, state).text();
    }

    public String defaultResult(String toolName, String argsJson, AgentSessionState state) {
        log.warn("Fallback for tool '{}' active – error reported to OTel", toolName);
        return "Tool '" + toolName + "' unavailable. Error logged to OTel.";
    }
}
