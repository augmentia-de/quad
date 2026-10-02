package de.augmentia.quad.core.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import de.augmentia.quad.core.session.AgentSessionState;

import java.lang.reflect.Method;

/**
 * A single executable tool.
 *
 * <p>One interface for reflective methods, MCP tools and stubs. Metadata lives on {@link #meta()}
 * rather than in annotations read back at execution time.
 */
public interface ToolMethod {

    ToolSpecification spec();

    /** Materialised metadata; the single source of truth for {@code @Idempotent} and friends. */
    default ToolMeta meta() {
        return ToolMeta.simple(spec().name(), spec().description());
    }

    ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception;

    static ToolMethod fromBean(Object bean, String methodName, Class<?>... paramTypes) {
        try {
            Method method = bean.getClass().getMethod(methodName, paramTypes);
            return new ReflectiveToolMethod(method, bean, new ToolArgsMapper(
                new com.fasterxml.jackson.databind.ObjectMapper()));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}