package de.augmentia.quad.core.capability.doc;

import de.augmentia.quad.core.annotation.Tool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

/**
 * Runtime agent introspection: generates an LLM-readable description of
 * agent capabilities (methods + tool descriptions).
 * <p>
 * Source: Python {@code src/quad/agentdoc/}, {@code src/quad/agent.py#__type_info__}
 */
public final class AgentDocExtractor {

    private AgentDocExtractor() {
    }

    public static String doc(Class<?> agentClass) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Agent Capabilities\n\n");
        sb.append("## ").append(agentClass.getSimpleName()).append("\n\n");

        String classDescription = classDescription(agentClass);
        if (classDescription != null) {
            sb.append("**Description:** ").append(classDescription).append("\n\n");
        }

        sb.append("## Methods:\n");
        for (Method method : agentClass.getDeclaredMethods()) {
            if (isGetterSetterOrFrameworkMethod(method)) {
                continue;
            }
            Tool projectTool = method.getAnnotation(Tool.class);
            dev.langchain4j.agent.tool.Tool lc4jTool =
                    method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
            String description = toolDescription(projectTool, lc4jTool);
            if (description != null) {
                sb.append("- `").append(method.getName()).append("(");
                Parameter[] parameters = method.getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(parameters[i].getType().getSimpleName());
                }
                sb.append(")` - ").append(description).append("\n");
            }
        }
        return sb.toString();
    }

    public static String doc(Object... objects) {
        StringBuilder sb = new StringBuilder();
        for (Object obj : objects) {
            if (obj instanceof Class<?> clazz) {
                sb.append(doc(clazz)).append("\n");
            } else if (obj != null) {
                sb.append(doc(obj.getClass())).append("\n");
            }
        }
        return sb.toString();
    }

    private static String classDescription(Class<?> agentClass) {
        Tool projectTool = agentClass.getAnnotation(Tool.class);
        if (projectTool != null && !projectTool.description().isBlank()) {
            return projectTool.description();
        }
        dev.langchain4j.agent.tool.Tool lc4jTool =
                agentClass.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
        return lc4jTool != null ? firstNonBlank(lc4jTool.value()) : null;
    }

    private static String toolDescription(Tool projectTool, dev.langchain4j.agent.tool.Tool lc4jTool) {
        if (projectTool != null && !projectTool.description().isBlank()) {
            return projectTool.description();
        }
        if (projectTool != null && !projectTool.value().isBlank()) {
            return projectTool.value();
        }
        return lc4jTool != null ? firstNonBlank(lc4jTool.value()) : null;
    }

    private static String firstNonBlank(String[] values) {
        if (values == null) {
            return null;
        }
        return Arrays.stream(values)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static boolean isGetterSetterOrFrameworkMethod(Method method) {
        String name = method.getName();
        return name.equals("toString") || name.equals("hashCode") || name.equals("equals")
                || name.startsWith("get") || name.startsWith("is") || name.startsWith("set");
    }
}
