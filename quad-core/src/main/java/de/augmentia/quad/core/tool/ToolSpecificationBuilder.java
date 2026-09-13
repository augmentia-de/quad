package de.augmentia.quad.core.tool;

import java.lang.reflect.Method;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

public class ToolSpecificationBuilder {
    public static ToolSpecification fromMethod(Method method) {
        return ToolSpecifications.toolSpecificationFrom(method);
    }

    public static ToolSpecification fromNameDescription(String name, String description) {
        return ToolSpecification.builder()
            .name(name)
            .description(description)
            .build();
    }
}