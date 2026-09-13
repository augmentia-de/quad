package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import de.augmentia.quad.core.annotation.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class ReflectiveToolMethod implements ToolMethod {

    private final ToolSpecification spec;
    private final Object beanInstance;
    private final ToolArgsMapper argsMapper;
    private final Method method;

    public ReflectiveToolMethod(ToolSpecification spec, Object beanInstance, ToolArgsMapper argsMapper) {
        this.spec = spec;
        this.beanInstance = beanInstance;
        this.argsMapper = argsMapper;
        this.method = findMethod(beanInstance.getClass(), spec.name());
    }

    public ReflectiveToolMethod(Method method, Object beanInstance, ToolArgsMapper argsMapper) {
        this.method = method;
        this.beanInstance = beanInstance;
        this.argsMapper = argsMapper;
        this.spec = buildSpec(method);
    }

    private ToolSpecification buildSpec(Method method) {
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();

        for (java.lang.reflect.Parameter param : method.getParameters()) {
            if (param.getType() == AgentSessionState.class) continue;

            Param paramAnno = param.getAnnotation(Param.class);
            String paramName = paramAnno != null && !paramAnno.value().isBlank()
                    ? paramAnno.value()
                    : param.getName();

            schemaBuilder.addProperty(paramName, toSchemaType(param.getType()));
            if (paramAnno == null || paramAnno.required()) {
                required.add(paramName);
            }
        }

        ToolSpecification.Builder specBuilder = ToolSpecification.builder()
                .name(method.getName())
                .description(buildDescription(method));

        if (!required.isEmpty()) {
            schemaBuilder.required(required.toArray(new String[0]));
            specBuilder.parameters(schemaBuilder.build());
        }

        return specBuilder.build();
    }

    private JsonSchemaElement toSchemaType(Class<?> type) {
        if (type == String.class) return JsonStringSchema.builder().build();
        if (type == Integer.class || type == int.class) return JsonIntegerSchema.builder().build();
        if (type == Long.class || type == long.class) return JsonIntegerSchema.builder().build();
        if (type == Double.class || type == double.class
                || type == Float.class || type == float.class) return JsonNumberSchema.builder().build();
        if (type == Boolean.class || type == boolean.class) return JsonBooleanSchema.builder().build();
        return JsonStringSchema.builder().build();
    }

    private String buildDescription(Method method) {
        var tool = method.getAnnotation(Tool.class);
        if (tool == null) return "";
        // Tools commonly annotate with @Tool("description"), which sets value()
        // rather than description(); prefer whichever is populated.
        if (tool.value() != null && !tool.value().isBlank()) return tool.value();
        return tool.description() != null ? tool.description() : "";
    }

    private Method findMethod(Class<?> clazz, String name) {
        // Prefer the annotated method higher up the hierarchy so a CDI client
        // proxy override (annotation-less) does not hide the real method.
        Method fallback = null;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    if (m.getAnnotation(Tool.class) != null) {
                        return m;
                    }
                    if (fallback == null) fallback = m;
                }
            }
        }
        if (fallback != null) return fallback;
        try {
            return clazz.getMethod(name);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Method not found: " + name, e);
        }
    }

    @Override
    public ToolSpecification spec() {
        return spec;
    }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception {
        CurrentSession.setCurrent(state);
        Object[] args = buildArgs(jsonArguments);
        try {
            Object result = method.invoke(beanInstance, args);
            if (result instanceof ToolResult tr) return tr;
            return ToolResult.success(result != null ? result.toString() : "");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    private Object[] buildArgs(String jsonArguments) {
        java.lang.reflect.Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];

        // AgentSessionState automatisch aus CurrentSession injizieren
        for (int i = 0; i < params.length; i++) {
            if (params[i].getType() == AgentSessionState.class) {
                args[i] = CurrentSession.getCurrent();
            }
        }

        if (jsonArguments == null || jsonArguments.isBlank()) {
            return args;
        }

        ObjectMapper mapper = argsMapper != null ? argsMapper.objectMapper() : new ObjectMapper();
        try {
            JsonNode root = mapper.readTree(jsonArguments);

            for (int i = 0; i < params.length; i++) {
                if (params[i].getType() == AgentSessionState.class) {
                    continue; // bereits injiziert
                }
                Param paramAnno = params[i].getAnnotation(Param.class);
                String paramName = paramAnno != null && !paramAnno.value().isBlank()
                        ? paramAnno.value()
                        : params[i].getName();

                JsonNode value = root.get(paramName);
                args[i] = mapValue(value, params[i].getType());
            }
            return args;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse arguments: " + jsonArguments, e);
        }
    }

    private Object mapValue(JsonNode node, Class<?> targetType) {
        if (node == null || node.isNull()) return null;

        if (targetType == String.class) return node.asText();
        if (targetType == Integer.class || targetType == int.class) return node.asInt();
        if (targetType == Long.class || targetType == long.class) return node.asLong();
        if (targetType == Double.class || targetType == double.class) return node.asDouble();
        if (targetType == Boolean.class || targetType == boolean.class) return node.asBoolean();

        return node.toString();
    }
}