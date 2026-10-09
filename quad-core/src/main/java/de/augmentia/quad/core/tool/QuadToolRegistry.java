package de.augmentia.quad.core.tool;

import de.augmentia.quad.core.agent.Agent;
import de.augmentia.quad.core.annotation.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * Registry of executable tools.
 *
 * <p>Not thread-safe by design: the concrete map is a plain {@link LinkedHashMap} guarded by the
 * instance lock, and reads hand out immutable snapshots. Tools are registered at startup and
 * mutated only through {@link ToolActivatorTool}, which is driven from a single agent turn.
 */
@ApplicationScoped
public class QuadToolRegistry {
    private static final Logger log = Logger.getLogger(QuadToolRegistry.class);

    private final Map<String, ToolMethod> tools = new LinkedHashMap<>();
    private final Map<Class<?>, List<String>> agentTools = new HashMap<>();
    private final ToolArgsMapper argsMapper;

    public QuadToolRegistry() {
        this.argsMapper = null;
    }

    public QuadToolRegistry(ToolArgsMapper argsMapper) {
        this.argsMapper = argsMapper;
    }

    @Inject
    public QuadToolRegistry(ToolArgsMapper argsMapper, Instance<BuiltInToolProvider> providers) {
        this.argsMapper = argsMapper;
        Set<String> registered = new LinkedHashSet<>();
        for (BuiltInToolProvider provider : providers) {
            for (ToolMethod tm : provider.getTools()) {
                if (registered.add(tm.spec().name())) {
                    register(tm.spec().name(), tm);
                } else {
                    log.warn("Tool name collision, provider '" + provider.providerName()
                            + "' skipped: " + tm.spec().name());
                }
            }
        }
    }

    /**
     * Registers a tool under an explicit name.
     *
     * <p>A collision is an error, not a silent overwrite: last-wins made it impossible to tell
     * which provider actually won.
     */
    public void register(String name, ToolMethod method) {
        ToolMethod existing = tools.get(name);
        if (existing != null && existing != method) {
            throw new DuplicateToolNameException(name,
                    describe(existing) + " is already registered; " + describe(method) + " rejected");
        }
        tools.put(name, method);
    }

    private static String describe(ToolMethod m) {
        return m.getClass().getSimpleName() + "(" + m.spec().name() + ")";
    }

    public void registerFromAgent(Agent agent) {
        for (Method m : agent.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                String name = m.getName();
                ToolMethod tm = new ReflectiveToolMethod(m, agent, argsMapper);
                register(name, tm);
                agentTools.computeIfAbsent(agent.getClass(), k -> new ArrayList<>()).add(name);
            }
        }
    }

    /**
     * Registers all {@code @Tool} methods of an arbitrary object under their method names.
     *
     * <p>Lets callers attach tools without subclassing {@link Agent}, e.g. via
     * {@code AgentBuilder.withToolObjects(...)}. A {@code null} bean is ignored.
     */
    public void registerFromObject(Object bean) {
        if (bean == null) return;
        for (Method m : bean.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(Tool.class)) {
                register(m.getName(), new ReflectiveToolMethod(m, bean, argsMapper));
            }
        }
    }

    public List<ToolMethod> getStaticToolsForAgent(Agent agent) {
        List<String> names = agentTools.get(agent.getClass());
        if (names == null) return List.of();
        return names.stream().map(tools::get).filter(Objects::nonNull).toList();
    }

    public void registerBuiltIn(BuiltInToolProvider provider) {
        for (ToolMethod tm : provider.getTools()) {
            register(tm.spec().name(), tm);
        }
    }

    public void remove(String name) {
        tools.remove(name);
        agentTools.values().forEach(list -> list.remove(name));
    }

    /** No-op: there is no CDI event bus on this path. Kept for interface compatibility. */
    public void notifyToolSetChanged(Set<String> added, Set<String> removed) {
    }

    /** Returns the tool, or {@code null} when unknown. Prefer {@link #find(String)}. */
    public ToolMethod get(String name) {
        return tools.get(name);
    }

    /** Null-safe lookup for callers that must distinguish "absent" from "null". */
    public Optional<ToolMethod> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public int size() {
        return tools.size();
    }

    public Set<String> getToolNames() {
        return Set.copyOf(tools.keySet());
    }

    public List<ToolMethod> getByNames(Collection<String> names) {
        return names.stream().map(tools::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public List<ToolMethod> getAll() {
        return List.copyOf(tools.values());
    }

    public QuadToolRegistry withOnly(Set<String> names) {
        QuadToolRegistry filtered = new QuadToolRegistry(argsMapper);
        for (String name : names) {
            ToolMethod tm = tools.get(name);
            if (tm != null) filtered.register(name, tm);
        }
        return filtered;
    }

    /** Tool specifications for the model. Hidden tools are excluded unless asked for. */
    public List<ToolSpecification> getSpecifications() {
        return getSpecifications(false);
    }

    public List<ToolSpecification> getSpecifications(boolean includeHidden) {
        return tools.values().stream()
                .filter(t -> includeHidden || t.meta() == null || !t.meta().hidden())
                .map(ToolMethod::spec)
                .collect(Collectors.toList());
    }

    /** Thrown when two tools claim the same name. */
    public static class DuplicateToolNameException extends IllegalStateException {
        public DuplicateToolNameException(String name, String detail) {
            super("Duplicate tool name '" + name + "': " + detail);
        }
    }
}