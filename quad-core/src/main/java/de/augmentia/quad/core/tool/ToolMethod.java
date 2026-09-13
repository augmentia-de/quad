package de.augmentia.quad.core.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import de.augmentia.quad.core.session.AgentSessionState;

import java.lang.reflect.Method;

public interface ToolMethod {
    ToolSpecification spec();
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