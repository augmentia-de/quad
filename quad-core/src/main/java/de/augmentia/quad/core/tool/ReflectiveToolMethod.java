package de.augmentia.quad.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.quad.core.annotation.Hidden;
import de.augmentia.quad.core.annotation.Idempotent;
import de.augmentia.quad.core.annotation.Param;
import de.augmentia.quad.core.annotation.Tool;
import de.augmentia.quad.core.annotation.ToolRole;
import de.augmentia.quad.core.session.AgentSessionState;
import de.augmentia.quad.core.session.CurrentSession;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tool backed by a {@code @Tool}-annotated Java method.
 *
 * <p>Three concerns are kept apart on purpose:
 * <ul>
 *   <li><b>Schema</b> — derived from the method signature and the {@code @Tool}/{@code @Param}
 *       annotations. Always produces a parameter schema, even when every parameter is optional.</li>
 *   <li><b>Binding</b> — delegated to Jackson {@code convertValue}, which handles
 *       {@code List}/{@code Map}/{@code Record}/{@code enum}/{@code POJO} recursively and fails
 *       loudly on a type mismatch instead of silently passing a {@code toString()} along.</li>
 *   <li><b>Invocation</b> — binds the session, invokes, restores the previous session in a
 *       {@code finally}. The session comes from the {@code state} argument; the ThreadLocal is only
 *       a fallback for tools that read it themselves.</li>
 * </ul>
 */
public class ReflectiveToolMethod implements ToolMethod {

    private final ToolSpecification spec;
    private final ToolMeta meta;
    private final Object beanInstance;
    private final ToolArgsMapper argsMapper;
    private final Method method;

    public ReflectiveToolMethod(ToolSpecification spec, Object beanInstance, ToolArgsMapper argsMapper) {
        this.method = findMethod(beanInstance.getClass(), spec.name());
        this.beanInstance = beanInstance;
        this.argsMapper = argsMapper;
        this.spec = spec;
        this.meta = buildMeta(spec.name(), method);
    }

    public ReflectiveToolMethod(Method method, Object beanInstance, ToolArgsMapper argsMapper) {
        this.method = method;
        this.beanInstance = beanInstance;
        this.argsMapper = argsMapper;
        this.meta = buildMeta(method.getName(), method);
        this.spec = buildSpec(method, meta);
    }

    private ObjectMapper mapper() {
        return argsMapper != null ? argsMapper.objectMapper() : new ObjectMapper();
    }

    // ── Schema ───────────────────────────────────────────────────────────────

    private ToolSpecification buildSpec(Method method, ToolMeta meta) {
        JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();

        for (Parameter param : method.getParameters()) {
            if (param.getType() == AgentSessionState.class) continue;

            String name = paramName(param);
            schema.addProperty(name, toSchemaType(param.getType(), param.getParameterizedType()));

            Param anno = param.getAnnotation(Param.class);
            // A parameter is required unless it is explicitly optional OR a primitive that has a
            // value supplied some other way. Primitives stay required: null is not assignable.
            if (anno == null || anno.required()) {
                required.add(name);
            }
        }

        // The schema is set unconditionally. An empty `required` list is the correct encoding for
        // "everything optional" — omitting `parameters` entirely hides the tool's arguments from
        // the model.
        if (!required.isEmpty()) {
            schema.required(required.toArray(new String[0]));
        }

        return ToolSpecification.builder()
                .name(method.getName())
                .description(meta.description())
                .parameters(schema.build())
                .build();
    }

    private String paramName(Parameter param) {
        Param anno = param.getAnnotation(Param.class);
        if (anno != null && !anno.value().isBlank()) {
            return anno.value();
        }
        // Requires -parameters (see quad-quarkus/pom.xml). Without it this is "arg0".
        return param.getName();
    }

    /**
     * Derives the JSON schema for one parameter type.
     *
     * <p>Falls back to a permissive object schema for types that are not directly expressible
     * (arbitrary POJOs, nested generics). That is deliberately better than claiming "string":
     * a wrong-but-open schema lets the model send the right structure, which Jackson then binds.
     */
    private JsonSchemaElement toSchemaType(Class<?> type, Type generic) {
        if (type == String.class || type == CharSequence.class || type == Character.class
                || type == char.class) {
            return JsonStringSchema.builder().build();
        }
        if (type == Integer.class || type == int.class || type == Short.class || type == short.class
                || type == Long.class || type == long.class || type == java.math.BigInteger.class) {
            return JsonIntegerSchema.builder().build();
        }
        if (type == Double.class || type == double.class || type == Float.class || type == float.class
                || type == java.math.BigDecimal.class) {
            return JsonNumberSchema.builder().build();
        }
        if (type == Boolean.class || type == boolean.class) {
            return JsonBooleanSchema.builder().build();
        }
        if (type.isEnum()) {
            return JsonEnumSchema.builder()
                    .enumValues(Arrays.stream(type.getEnumConstants())
                            .map(Object::toString).toList())
                    .build();
        }
        if (type == List.class || type == java.util.Collection.class || type == java.util.Set.class
                || type.isArray() && type != byte[].class) {
            JsonSchemaElement items = arrayItemSchema(type, generic);
            return JsonArraySchema.builder().items(items).build();
        }
        if (Map.class.isAssignableFrom(type)) {
            return JsonObjectSchema.builder().build();
        }
        if (type.isArray()) {
            return JsonStringSchema.builder().build();
        }
        // Record / POJO / anything else: an open object lets the model send a structure that
        // Jackson can bind, instead of a "string" the method would then reject.
        return JsonObjectSchema.builder().build();
    }

    private JsonSchemaElement arrayItemSchema(Class<?> type, Type generic) {
        if (type.isArray() && type.getComponentType() != byte.class) {
            return toSchemaType(type.getComponentType(), null);
        }
        if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1) {
            Type arg = pt.getActualTypeArguments()[0];
            if (arg instanceof Class<?> c) {
                return toSchemaType(c, null);
            }
            if (arg instanceof ParameterizedType inner) {
                return toSchemaType((Class<?>) inner.getRawType(), inner);
            }
        }
        return JsonStringSchema.builder().build();
    }

    private ToolMeta buildMeta(String name, Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        String description = "";
        if (tool != null) {
            // @Tool("text") sets value(); @Tool(description = "text") sets description().
            description = !tool.value().isBlank() ? tool.value()
                    : tool.description() != null ? tool.description() : "";
        }
        boolean hidden = method.isAnnotationPresent(Hidden.class)
                || (method.getDeclaringClass() != null
                    && method.getDeclaringClass().isAnnotationPresent(Hidden.class));
        ToolRole role = method.getAnnotation(ToolRole.class);
        Set<String> roles = role == null || role.allowedRoles().length == 0
                ? Set.of() : new LinkedHashSet<>(Arrays.asList(role.allowedRoles()));
        return new ToolMeta(
                name,
                description,
                method.isAnnotationPresent(Idempotent.class),
                hidden,
                roles,
                null,
                specOrNull());
    }

    /** Kept so {@link #buildMeta} can be called before the spec exists. */
    private ToolSpecification specOrNull() {
        return null;
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

    // ── ToolMethod ───────────────────────────────────────────────────────────

    @Override
    public ToolSpecification spec() {
        return spec;
    }

    @Override
    public ToolMeta meta() {
        return meta;
    }

    @Override
    public ToolResult execute(String jsonArguments, AgentSessionState state) throws Exception {
        // Save/restore rather than set/clear: the caller (kafka-platform TaskExecutor) owns the
        // binding for the whole task and must not lose it to a nested tool call.
        AgentSessionState previous = CurrentSession.getCurrent();
        if (state != null) {
            CurrentSession.setCurrent(state);
        }
        try {
            Object[] args = buildArgs(jsonArguments, state);
            Object result = method.invoke(beanInstance, args);
            return toResult(result);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(cause);
        } finally {
            CurrentSession.setCurrent(previous);
        }
    }

    private ToolResult toResult(Object raw) {
        if (raw instanceof ToolResult r) return r;
        if (raw instanceof String s) return ToolResult.success(s);
        if (raw == null) return ToolResult.success("");
        // POJO/Record/List: a toString() here would hand the model "[Edit[filePath=a]]" instead of
        // structure. JSON is the only machine-readable form.
        return ToolResult.json(mapper().valueToTree(raw));
    }

    private Object[] buildArgs(String jsonArguments, AgentSessionState state) {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];

        boolean hasArgs = jsonArguments != null && !jsonArguments.isBlank();
        JsonNode root = hasArgs ? readTree(jsonArguments) : null;

        for (int i = 0; i < params.length; i++) {
            if (params[i].getType() == AgentSessionState.class) {
                // The argument wins over the ThreadLocal (TODO2 A7): a caller that passes a
                // different state must not be overruled by ambient thread state.
                args[i] = state != null ? state : CurrentSession.getCurrent();
                continue;
            }
            if (root == null) {
                args[i] = params[i].getType().isPrimitive() ? primitiveDefault(params[i]) : null;
                continue;
            }

            JsonNode value = root.get(paramName(params[i]));
            if (value == null || value.isNull()) {
                if (params[i].getType().isPrimitive()) {
                    throw new IllegalArgumentException(
                            "Missing required argument '" + paramName(params[i]) + "' for tool '"
                                    + spec.name() + "'");
                }
                args[i] = null;
                continue;
            }
            args[i] = convert(value, params[i]);
        }
        return args;
    }

    private Object primitiveDefault(Parameter param) {
        Class<?> t = param.getType();
        if (t == boolean.class) return Boolean.FALSE;
        if (t == char.class) return (char) 0;
        return 0; // autoboxes to the right wrapper per target type
    }

    private JsonNode readTree(String jsonArguments) {
        try {
            return mapper().readTree(jsonArguments);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot parse arguments for tool '" + spec.name() + "': " + jsonArguments, e);
        }
    }

    private Object convert(JsonNode value, Parameter param) {
        try {
            return mapper().convertValue(value, mapper().constructType(param.getParameterizedType()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Argument '" + paramName(param) + "' of tool '" + spec.name()
                            + "' does not match " + param.getType().getSimpleName()
                            + ": " + e.getMessage(), e);
        }
    }
}